package io.lexq.sdk;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a response body as JSON (RFC 8259). It creates only {@code LinkedHashMap} with
 * {@code String} keys, {@code ArrayList}, {@code String}, {@code BigDecimal}, {@code Boolean}, and
 * {@code null}, whatever the keys are named. Every number becomes a {@code BigDecimal} with the
 * digits and the scale as written, so {@code 1.50} keeps its scale of 2.
 *
 * <p>The body must be UTF-8. When an object repeats a key, the last value wins, as in JavaScript's
 * {@code JSON.parse}. The reader keeps its own stack instead of calling itself, so no nesting
 * overflows the thread's stack.
 */
final class JsonReader {
    private JsonReader() {}

    /**
     * The longest number the reader accepts, in characters. {@code new BigDecimal(String)} takes time
     * that grows with the square of the number's length, so one long number could hold up the
     * caller's thread long after the body arrived.
     */
    static final int MAX_NUMBER_LENGTH = 1000;

    /** The value the body holds. */
    static Object read(byte[] body) throws UnreadableJsonException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new UnreadableJsonException("The body is not UTF-8.");
        }
        return new Parser(text).document();
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Object document() throws UnreadableJsonException {
            Object value = value();
            space();
            if (at != text.length()) {
                throw error("unexpected text after the value");
            }
            return value;
        }

        /** An object or a list being read, and for an object, the key whose value comes next. */
        private static final class Open {
            final Map<String, Object> object;
            final List<Object> list;
            String key;

            Open(Map<String, Object> object, List<Object> list) {
                this.object = object;
                this.list = list;
            }
        }

        private Object value() throws UnreadableJsonException {
            ArrayDeque<Open> open = new ArrayDeque<>();
            while (true) {
                // Read the start of a value. An object or a list that is not empty stays open.
                space();
                Object value;
                char c = next();
                if (c == '{') {
                    space();
                    if (take('}')) {
                        value = new LinkedHashMap<String, Object>();
                    } else {
                        Open object = new Open(new LinkedHashMap<>(), null);
                        object.key = key();
                        open.push(object);
                        continue;
                    }
                } else if (c == '[') {
                    space();
                    if (take(']')) {
                        value = new ArrayList<>();
                    } else {
                        open.push(new Open(null, new ArrayList<>()));
                        continue;
                    }
                } else {
                    value = scalar(c);
                }

                // Put the finished value where it belongs, and close every object or list it ends.
                while (true) {
                    Open parent = open.peek();
                    if (parent == null) {
                        return value;
                    }
                    if (parent.object != null) {
                        parent.object.put(parent.key, value);
                    } else {
                        parent.list.add(value);
                    }
                    space();
                    if (take(',')) {
                        if (parent.object != null) {
                            space();
                            parent.key = key();
                        }
                        break;
                    }
                    if (parent.object != null ? !take('}') : !take(']')) {
                        throw error(parent.object != null ? "expected , or }" : "expected , or ]");
                    }
                    value = parent.object != null ? parent.object : parent.list;
                    open.pop();
                }
            }
        }

        /** A key and the colon after it. */
        private String key() throws UnreadableJsonException {
            if (!take('"')) {
                throw error("expected a key");
            }
            String key = string();
            space();
            if (!take(':')) {
                throw error("expected :");
            }
            return key;
        }

        private Object scalar(char c) throws UnreadableJsonException {
            switch (c) {
                case '"':
                    return string();
                case 't':
                    return word("rue", Boolean.TRUE);
                case 'f':
                    return word("alse", Boolean.FALSE);
                case 'n':
                    return word("ull", null);
                default:
                    if (c == '-' || c >= '0' && c <= '9') {
                        return number(at - 1);
                    }
                    at--;
                    throw error("unexpected character");
            }
        }

        /** The rest of a string whose opening quote has been read. */
        private String string() throws UnreadableJsonException {
            StringBuilder out = null;
            int done = at;
            while (true) {
                if (at >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(at);
                if (c == '"') {
                    String result = out == null ? text.substring(done, at) : out.append(text, done, at).toString();
                    at++;
                    return result;
                }
                if (c < 0x20) {
                    throw error("control character in a string");
                }
                if (c != '\\') {
                    at++;
                    continue;
                }
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(text, done, at);
                at++;
                if (at >= text.length()) {
                    throw error("unterminated string");
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(hex());
                    default -> {
                        at -= 2;
                        throw error("bad escape");
                    }
                }
                done = at;
            }
        }

        /** The code unit that the four hex digits after a {@code \}{@code u} write. */
        private char hex() throws UnreadableJsonException {
            if (at + 4 > text.length()) {
                throw error("bad \\u escape");
            }
            int unit = 0;
            for (int i = 0; i < 4; i++) {
                char c = text.charAt(at + i);
                int digit = c >= '0' && c <= '9' ? c - '0'
                        : c >= 'a' && c <= 'f' ? c - 'a' + 10
                        : c >= 'A' && c <= 'F' ? c - 'A' + 10
                        : -1;
                if (digit < 0) {
                    throw error("bad \\u escape");
                }
                unit = unit << 4 | digit;
            }
            at += 4;
            return (char) unit;
        }

        /** A number that starts at {@code start}, read as RFC 8259 writes it. */
        private BigDecimal number(int start) throws UnreadableJsonException {
            at = start;
            take('-');
            if (!take('0')) {
                if (!digits()) {
                    throw error("bad number");
                }
            }
            if (take('.') && !digits()) {
                throw error("bad number");
            }
            boolean negativeExponent = false;
            int exponent = -1;
            if (take('e') || take('E')) {
                negativeExponent = take('-');
                if (!negativeExponent) {
                    take('+');
                }
                exponent = at;
                if (!digits()) {
                    throw error("bad number");
                }
            }
            if (at - start > MAX_NUMBER_LENGTH) {
                at = start;
                throw error("a number longer than " + MAX_NUMBER_LENGTH + " characters");
            }
            // JDK 17 refuses an exponent outside the range of an int even when the scale would fit,
            // as in 1.5E+2147483648, which JDK 21 and 25 read. The reader refuses it on every JDK.
            // BigDecimal then refuses a scale outside that range on all three, as in 1E-2147483648.
            if (exponent >= 0 && !fitsInInt(exponent, at, negativeExponent)) {
                at = start;
                throw error("a number whose exponent is out of range");
            }
            try {
                return new BigDecimal(text.substring(start, at));
            } catch (NumberFormatException e) {
                at = start;
                throw error("a number whose exponent is out of range");
            }
        }

        /** Whether the digits from {@code from} to {@code to}, with the sign, are a value an int holds. */
        private boolean fitsInInt(int from, int to, boolean negative) {
            while (from < to - 1 && text.charAt(from) == '0') {
                from++;
            }
            if (to - from > 10) {
                return false;
            }
            long value = Long.parseLong(text, from, to, 10);
            return value <= (negative ? -(long) Integer.MIN_VALUE : Integer.MAX_VALUE);
        }

        /** Skips the digits at the current position, and says whether there was at least one. */
        private boolean digits() {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                at++;
            }
            return at > start;
        }

        private Object word(String rest, Object value) throws UnreadableJsonException {
            if (!text.startsWith(rest, at)) {
                at--;
                throw error("unexpected character");
            }
            at += rest.length();
            return value;
        }

        private char next() throws UnreadableJsonException {
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            return text.charAt(at++);
        }

        private boolean take(char c) {
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void space() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                at++;
            }
        }

        private UnreadableJsonException error(String problem) {
            return new UnreadableJsonException("The body cannot be read as JSON: " + problem + " at offset " + at + ".");
        }
    }
}
