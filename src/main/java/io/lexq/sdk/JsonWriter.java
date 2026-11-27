package io.lexq.sdk;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes the request body, {@code {"facts":{...},"contexts":{...}}}, once, so every attempt of a
 * call sends the same bytes.
 *
 * <p>The facts and contexts may hold a {@code Map} with {@code String} keys, a {@code List}, a
 * {@code String}, a {@code Boolean}, {@code null}, a {@code BigDecimal}, a {@code BigInteger}, a
 * {@code Long}, an {@code Integer}, a {@code Short}, a {@code Byte}, a {@code Double}, and a
 * {@code Float}. A {@code null} is written as {@code null} at every level and keeps its key. A
 * string is escaped as JavaScript's {@code JSON.stringify} escapes it, so an unpaired surrogate is
 * written as a {@code \}{@code uXXXX} escape instead of turning into {@code ?} in UTF-8.
 *
 * <p>These throw {@link UnsendableValueException}: NaN, an infinity, a map key that is not
 * a string, a collection that is not a {@code List}, a value of any other type, a structure that
 * contains itself, and a value that would nest the body deeper than LexQ allows. The client then
 * refuses the request as {@code invalid_input}.
 */
final class JsonWriter {
    private JsonWriter() {}

    /** The level of the facts and the contexts: inside the body's outermost braces. */
    private static final int OBJECT_LEVEL = 2;

    /** A type name as {@code Class.getTypeName()} writes it for a class with an ordinary name. */
    private static final Pattern PLAIN_TYPE_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:\\[])*");
    private static final int MAX_TYPE_NAME_LENGTH = 200;

    /**
     * The body as UTF-8, with {@code contexts} left out when it is null.
     *
     * @throws UnsendableValueException when the facts or contexts hold a value the client does not send
     */
    static byte[] body(Map<?, ?> facts, Map<?, ?> contexts) {
        Objects.requireNonNull(facts, "facts");
        Writer writer = new Writer();
        writer.out.append("{\"facts\":");
        writer.top("facts", facts);
        if (contexts != null) {
            writer.out.append(",\"contexts\":");
            writer.top("contexts", contexts);
        }
        writer.out.append('}');
        return writer.out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class Writer {
        final StringBuilder out = new StringBuilder(256);
        /** Where the value being written is: the top-level name, then each key or list index. */
        final List<Object> path = new ArrayList<>();
        /** The maps and lists that hold the value being written. */
        final Set<Object> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());

        void top(String name, Map<?, ?> map) {
            path.add(name);
            container(map, OBJECT_LEVEL);
            path.remove(path.size() - 1);
        }

        /** Writes a value whose maps or lists would sit at the given level of the body. */
        void value(Object value, int level) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String text) {
                string(text);
            } else if (value instanceof Boolean flag) {
                out.append(flag.booleanValue());
            } else if (value instanceof Double number) {
                requireFinite(number);
                out.append(NumberText.of(number.doubleValue()));
            } else if (value instanceof Float number) {
                requireFinite(number.doubleValue());
                out.append(NumberText.of(number.floatValue()));
            } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                    || value instanceof Byte || value.getClass() == BigInteger.class
                    || value.getClass() == BigDecimal.class) {
                // A subclass of BigInteger or BigDecimal can override toString(), so only the
                // classes themselves are written this way.
                out.append(value);
            } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                container(value, level);
            } else if (value instanceof Collection<?>) {
                throw refused(typed(value, ", a collection whose order the client cannot rely on",
                        "is a collection whose order the client cannot rely on"));
            } else {
                throw refused(typed(value, ", which the client does not send", "has a type the client does not send"));
            }
        }

        void requireFinite(double number) {
            if (Double.isNaN(number)) {
                throw refused("is NaN, which JSON cannot carry");
            }
            if (Double.isInfinite(number)) {
                throw refused("is " + (number > 0 ? "Infinity" : "-Infinity") + ", which JSON cannot carry");
            }
        }

        void container(Object container, int level) {
            if (level > Contract.MAX_REQUEST_NESTING_DEPTH) {
                throw refused("would nest the request body more than " + Contract.MAX_REQUEST_NESTING_DEPTH
                        + " levels deep");
            }
            if (!ancestors.add(container)) {
                throw refused("contains itself, which JSON cannot carry");
            }
            if (container instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw refused("has a key that is not a string, which JSON cannot carry");
                    }
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(key);
                    out.append(':');
                    path.add(key);
                    value(entry.getValue(), level + 1);
                    path.remove(path.size() - 1);
                }
                out.append('}');
            } else {
                out.append('[');
                int index = 0;
                for (Object item : (List<?>) container) {
                    if (index > 0) {
                        out.append(',');
                    }
                    path.add(index++);
                    value(item, level + 1);
                    path.remove(path.size() - 1);
                }
                out.append(']');
            }
            ancestors.remove(container);
        }

        /** Writes the string as {@code JSON.stringify} does. */
        void string(String text) {
            out.append('"');
            int done = 0;
            int length = text.length();
            for (int i = 0; i < length; i++) {
                char c = text.charAt(i);
                if (c >= 0x20 && c != '"' && c != '\\' && !Character.isSurrogate(c)) {
                    continue;
                }
                if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                out.append(text, done, i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> Text.escape(out, c);
                }
                done = i + 1;
            }
            out.append(text, done, length).append('"');
        }

        /** The refusal of the value at the current path, with the path on one line and cut to the default length. */
        UnsendableValueException refused(String problem) {
            StringBuilder where = new StringBuilder();
            for (Object step : path) {
                if (where.length() > Contract.MAX_KEY_PATH_LENGTH) {
                    break;
                }
                if (step instanceof Integer index) {
                    where.append('[').append(index.intValue()).append(']');
                } else {
                    if (where.length() > 0) {
                        where.append('.');
                    }
                    where.append((String) step);
                }
            }
            // Escaping can make the path longer, so only the part that can be shown is escaped. The
            // result is the same as escaping the whole path and then cutting it.
            String shown = where.substring(0, Math.min(where.length(), Contract.MAX_KEY_PATH_LENGTH + 1));
            return new UnsendableValueException(
                    Text.cut(Text.escaped(shown), Contract.MAX_KEY_PATH_LENGTH) + " " + problem);
        }

        /**
         * "has type", the value's type name, and {@code afterName}, or {@code unnamed} when the type
         * has no ordinary name of at most 200 characters, such as a lambda's class.
         */
        private static String typed(Object value, String afterName, String unnamed) {
            String name = value.getClass().getTypeName();
            return name.length() <= MAX_TYPE_NAME_LENGTH && PLAIN_TYPE_NAME.matcher(name).matches()
                    ? "has type " + name + afterName
                    : unnamed;
        }
    }
}
