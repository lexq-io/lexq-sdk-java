import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes src/main/java/io/lexq/sdk/Contract.java from spec/values.json, the values of the LexQ
 * SDK specification version that spec/spec.lock records. Contract.java holds the LexQ constants
 * that CONSTANTS lists and every default in DEFAULTS. Run it from the repository root, or pass
 * the root as the last argument:
 *
 * <pre>
 * java scripts/GenerateContract.java [repository root]           write Contract.java
 * java scripts/GenerateContract.java --check [repository root]   check that Contract.java matches
 * </pre>
 *
 * <p>A constant, default, or error code named below that spec/values.json lacks, or a default in
 * spec/values.json that DEFAULTS does not list, fails the run. The client would otherwise rely
 * on a value the specification no longer states, or leave out one it now states. The run exits
 * with status 1 on any failure, such as a missing or malformed value, or a Contract.java that
 * --check finds different.
 */
public final class GenerateContract {
    private static final String VALUES = "spec/values.json";
    private static final String SOURCE = "src/main/java/io/lexq/sdk/Contract.java";

    /** What a default holds, and so how Contract.java declares it. */
    private enum Kind {
        URL("String", "a URL that starts with https:// and holds only printable ASCII, with no spaces"),
        COUNT("int", "a positive integer no larger than " + Integer.MAX_VALUE),
        MILLIS("long", "a positive integer number of milliseconds"),
        RANGE("Range", "an object with only minMs and maxMs, positive integers with minMs at most maxMs"),
        RANGES("List<Range>", "a non-empty list of objects, each with only minMs and maxMs, positive integers"
                + " with minMs at most maxMs");

        final String type;
        final String expected;

        Kind(String type, String expected) {
            this.type = type;
            this.expected = expected;
        }
    }

    private record Constant(String name, String doc) {}

    private record Default(String key, String name, Kind kind, String doc) {}

    private record Values(Map<String, Object> constants, Map<String, Integer> statuses, Map<String, Object> defaults) {}

    /**
     * The LexQ constants Contract.java carries, by the name LexQ gives them, and what each one is.
     * A code such as C-012 written in angle brackets in a description becomes that code with the
     * status spec/values.json gives it.
     */
    private static final List<Constant> CONSTANTS = List.of(
            new Constant("MAX_REQUEST_NESTING_DEPTH",
                    "How many levels of objects and lists a request body may nest, counting its outermost"
                            + " braces. LexQ answers a deeper body with <C-012>."),
            new Constant("IDEMPOTENCY_KEY_MAX_LENGTH",
                    "The longest {@code Idempotency-Key} LexQ accepts, in characters. LexQ answers a longer key"
                            + " with <I-003>."),
            new Constant("REPLAY_RETENTION_HOURS",
                    "How long a successful decision stays available to a repeat with the same"
                            + " {@code Idempotency-Key}, in hours. A later repeat is handled as a new call and is"
                            + " billed if it runs."));

    /** A code in a CONSTANTS description, written in angle brackets. */
    private static final Pattern CODE_IN_DOC = Pattern.compile("<([A-Z]{1,4}-\\d{3})>");

    /**
     * Every default in the specification: its key in spec/values.json, its name in Contract.java,
     * its kind, and what it is.
     */
    private static final List<Default> DEFAULTS = List.of(
            new Default("baseUrl", "BASE_URL", Kind.URL, "Where LexQ is."),
            new Default("deadlineMs", "DEADLINE_MS", Kind.MILLIS,
                    "The deadline for a call, in milliseconds, covering every attempt and every wait between them."),
            new Default("attemptTimeoutMs", "ATTEMPT_TIMEOUT_MS", Kind.MILLIS,
                    "The timeout for one attempt while retries are on, in milliseconds."),
            new Default("maxAttempts", "MAX_ATTEMPTS", Kind.COUNT, "Attempts for a call, counting the first."),
            new Default("backoff", "BACKOFF", Kind.RANGES,
                    "The wait before the next attempt, in milliseconds: entry n is the range after n + 1 failed"
                            + " attempts."),
            new Default("rateLimitWait", "RATE_LIMIT_WAIT", Kind.RANGE,
                    "The wait after a {@code 429} without {@code Retry-After}, in milliseconds."),
            new Default("keyInUseWait", "KEY_IN_USE_WAIT", Kind.RANGE, "The wait after {@code I-002}, in milliseconds."),
            new Default("breakerThreshold", "BREAKER_THRESHOLD", Kind.COUNT,
                    "How many counted calls in a row open the breaker."),
            new Default("breakerCooldownMs", "BREAKER_COOLDOWN_MS", Kind.MILLIS,
                    "How long the breaker stays open before one trial call goes through, in milliseconds."),
            new Default("maxResponseBytes", "MAX_RESPONSE_BYTES", Kind.COUNT,
                    "The largest response body the client reads, in bytes."),
            new Default("maxMessageLength", "MAX_MESSAGE_LENGTH", Kind.COUNT,
                    "The longest part of LexQ's {@code message} an error message keeps, in UTF-16 code units."),
            new Default("maxKeyPathLength", "MAX_KEY_PATH_LENGTH", Kind.COUNT,
                    "The longest key path an error message keeps, in UTF-16 code units."),
            new Default("maxErrorCodeLength", "MAX_ERROR_CODE_LENGTH", Kind.COUNT,
                    "The longest {@code errorCode} an error keeps, in characters."));

    public static void main(String[] args) throws IOException {
        List<String> rest = new ArrayList<>(List.of(args));
        boolean check = rest.remove("--check");
        if (rest.size() > 1) {
            fail("Usage: java scripts/GenerateContract.java [--check] [repository root]", List.of());
        }
        Path root = Path.of(rest.isEmpty() ? "." : rest.get(0));
        String rendered = render(read(root.resolve(VALUES)));
        Path source = root.resolve(SOURCE);
        String summary = CONSTANTS.size() + " constants, " + DEFAULTS.size() + " defaults.";
        if (check) {
            String current = Files.exists(source) ? Files.readString(source, StandardCharsets.UTF_8) : null;
            if (!rendered.equals(current)) {
                fail(SOURCE + " does not match " + VALUES + ".",
                        List.of("It was changed by hand or not written again. Run java scripts/GenerateContract.java"
                                + " to write it."));
            }
            System.out.println(SOURCE + " matches " + VALUES + ": " + summary);
        } else {
            Files.createDirectories(source.getParent());
            Files.writeString(source, rendered, StandardCharsets.UTF_8);
            System.out.println("Wrote " + SOURCE + " from " + VALUES + ": " + summary);
        }
    }

    /** The values Contract.java needs, checked for presence and form. */
    private static Values read(Path path) throws IOException {
        String text = null;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            fail(VALUES + " not found.", List.of("It belongs in spec/ with spec/SPEC.md and spec/spec.lock."
                    + " CONTRIBUTING.md says how spec/ changes."));
        }
        Object document = null;
        try {
            document = new Json(text).document();
        } catch (IllegalArgumentException e) {
            fail(VALUES + " is not valid JSON: " + e.getMessage(), List.of());
        }
        Map<String, Object> top = object(document);
        Map<String, Object> constants = object(top.get("constants"));
        Map<String, Object> defaults = object(top.get("defaults"));
        Map<String, Integer> statuses = new LinkedHashMap<>();
        if (top.get("errorCodes") instanceof List<?> entries) {
            for (Object entry : entries) {
                Map<String, Object> item = object(entry);
                if (item.get("code") instanceof String code && isCount(item.get("httpStatus"), Integer.MAX_VALUE)) {
                    statuses.put(code, ((BigDecimal) item.get("httpStatus")).intValueExact());
                }
            }
        }

        List<String> problems = new ArrayList<>();
        for (Constant constant : CONSTANTS) {
            if (!isCount(constants.get(constant.name()), Integer.MAX_VALUE)) {
                problems.add("constant " + constant.name() + " is missing or not a positive integer no larger than "
                        + Integer.MAX_VALUE);
            }
            Matcher codes = CODE_IN_DOC.matcher(constant.doc());
            while (codes.find()) {
                if (!statuses.containsKey(codes.group(1))) {
                    problems.add("error code " + codes.group(1) + ", which the description of " + constant.name()
                            + " names, is missing or its status is not an integer");
                }
            }
        }
        for (String key : defaults.keySet()) {
            if (DEFAULTS.stream().noneMatch(listed -> listed.key().equals(key))) {
                problems.add("default " + key + " is not in DEFAULTS");
            }
        }
        for (Default listed : DEFAULTS) {
            if (!fits(listed.kind(), defaults.get(listed.key()))) {
                problems.add("default " + listed.key() + " is missing or not " + listed.kind().expected);
            }
        }
        if (defaults.get("backoff") instanceof List<?> backoff && isCount(defaults.get("maxAttempts"), Integer.MAX_VALUE)) {
            int attempts = ((BigDecimal) defaults.get("maxAttempts")).intValueExact();
            if (backoff.size() != attempts - 1) {
                problems.add("default backoff has " + backoff.size() + " ranges, and maxAttempts " + attempts
                        + " needs " + (attempts - 1));
            }
        }
        if (!problems.isEmpty()) {
            fail(VALUES + " does not hold the values this client uses.", problems);
        }
        return new Values(constants, statuses, defaults);
    }

    /** The object a JSON value is, or an empty one when it is not an object. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static boolean isCount(Object value, long max) {
        return value instanceof BigDecimal number
                && number.signum() > 0
                && number.stripTrailingZeros().scale() <= 0
                && number.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    private static boolean isRange(Object value) {
        Map<String, Object> range = object(value);
        return value instanceof Map<?, ?>
                && range.keySet().equals(Set.of("minMs", "maxMs"))
                && isCount(range.get("minMs"), Long.MAX_VALUE)
                && isCount(range.get("maxMs"), Long.MAX_VALUE)
                && ((BigDecimal) range.get("minMs")).compareTo((BigDecimal) range.get("maxMs")) <= 0;
    }

    private static boolean fits(Kind kind, Object value) {
        return switch (kind) {
            case URL -> value instanceof String url && url.startsWith("https://")
                    && url.chars().allMatch(c -> c > 0x20 && c < 0x7f);
            case COUNT -> isCount(value, Integer.MAX_VALUE);
            case MILLIS -> isCount(value, Long.MAX_VALUE);
            case RANGE -> isRange(value);
            case RANGES -> value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(GenerateContract::isRange);
        };
    }

    private static String render(Values values) {
        StringBuilder out = new StringBuilder();
        out.append("// Generated by scripts/GenerateContract.java from spec/values.json. Do not edit.\n");
        out.append("package io.lexq.sdk;\n\n");
        out.append("import java.util.List;\n\n");
        out.append("/** The values of the LexQ SDK specification that the client uses, from {@code spec/values.json}. */\n");
        out.append("final class Contract {\n");
        out.append("    private Contract() {}\n\n");
        out.append("    /** A wait of at least {@code minMs} and at most {@code maxMs} milliseconds. */\n");
        out.append("    record Range(long minMs, long maxMs) {}\n");
        for (Constant constant : CONSTANTS) {
            Matcher codes = CODE_IN_DOC.matcher(constant.doc());
            String doc = codes.replaceAll(match -> Matcher.quoteReplacement(
                    "{@code " + values.statuses().get(match.group(1)) + " " + match.group(1) + "}"));
            out.append("\n    /** ").append(doc).append(" */\n");
            out.append("    static final int ").append(constant.name()).append(" = ")
                    .append(integer(values.constants().get(constant.name()))).append(";\n");
        }
        for (Default listed : DEFAULTS) {
            out.append("\n    /** ").append(listed.doc()).append(" */\n");
            out.append("    static final ").append(listed.kind().type).append(' ').append(listed.name()).append(" = ")
                    .append(literal(listed.kind(), values.defaults().get(listed.key()))).append(";\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static String literal(Kind kind, Object value) {
        return switch (kind) {
            case URL -> javaString((String) value);
            case COUNT -> integer(value);
            case MILLIS -> integer(value) + "L";
            case RANGE -> range(value);
            case RANGES -> {
                List<String> ranges = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    ranges.add(range(item));
                }
                yield "List.of(" + String.join(", ", ranges) + ")";
            }
        };
    }

    private static String range(Object value) {
        Map<String, Object> range = object(value);
        return "new Range(" + integer(range.get("minMs")) + "L, " + integer(range.get("maxMs")) + "L)";
    }

    private static String integer(Object value) {
        return ((BigDecimal) value).toBigIntegerExact().toString();
    }

    /**
     * A Java string literal for the text, with every character outside printable ASCII escaped. An
     * ASCII control character takes an octal escape. javac replaces a {@code \}{@code u} escape
     * with its character before it reads the literal, so a line feed written as one would split
     * the literal across two lines, which does not compile.
     */
    private static String javaString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c >= 0x20 && c < 0x7f) {
                out.append(c);
            } else if (c < 0x20 || c == 0x7f) {
                out.append(String.format("\\%03o", (int) c));
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.append('"').toString();
    }

    private static void fail(String message, List<String> details) {
        System.err.println(message);
        for (String detail : details) {
            System.err.println("  " + detail);
        }
        System.exit(1);
    }

    /** A strict reader for spec/values.json: objects, arrays, strings, numbers, true, false, and null. */
    private static final class Json {
        private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
        private static final int MAX_DEPTH = 64;

        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object document() {
            Object value = value(0);
            space();
            if (at != text.length()) {
                throw error("unexpected text after the value");
            }
            return value;
        }

        /** A value inside the given number of enclosing objects and arrays. */
        private Object value(int depth) {
            space();
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(at);
            if ((c == '{' || c == '[') && depth >= MAX_DEPTH) {
                throw error("nested deeper than " + MAX_DEPTH + " levels");
            }
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character");
                }
            };
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> members = new LinkedHashMap<>();
            at++;
            space();
            if (peek('}')) {
                at++;
                return members;
            }
            while (true) {
                space();
                if (!peek('"')) {
                    throw error("expected a member name");
                }
                String name = string();
                space();
                expect(':');
                if (members.containsKey(name)) {
                    throw error("duplicate member " + name);
                }
                members.put(name, value(depth + 1));
                space();
                if (peek(',')) {
                    at++;
                } else {
                    expect('}');
                    return members;
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> items = new ArrayList<>();
            at++;
            space();
            if (peek(']')) {
                at++;
                return items;
            }
            while (true) {
                items.add(value(depth + 1));
                space();
                if (peek(',')) {
                    at++;
                } else {
                    expect(']');
                    return items;
                }
            }
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            at++;
            while (true) {
                if (at >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("control character in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length() || !text.substring(at, at + 4).matches("[0-9A-Fa-f]{4}")) {
                            throw error("bad \\u escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
        }

        private BigDecimal number() {
            Matcher matcher = NUMBER.matcher(text).region(at, text.length());
            if (!matcher.lookingAt()) {
                throw error("bad number");
            }
            at = matcher.end();
            return new BigDecimal(matcher.group());
        }

        private Object word(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw error("unexpected character");
            }
            at += word.length();
            return value;
        }

        private void space() {
            while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected " + c);
            }
            at++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + at);
        }
    }
}
