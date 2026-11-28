package io.lexq.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class JsonWriterTest {
    private static final int MAX_DEPTH = Contract.MAX_REQUEST_NESTING_DEPTH;
    private static final int MAX_PATH = Contract.MAX_KEY_PATH_LENGTH;

    private static String body(Map<?, ?> facts, Map<?, ?> contexts) {
        return new String(JsonWriter.body(facts, contexts), StandardCharsets.UTF_8);
    }

    private static String refusal(Map<?, ?> facts, Map<?, ?> contexts) {
        return assertThrows(UnsendableValueException.class, () -> JsonWriter.body(facts, contexts)).getMessage();
    }

    /** A map that keeps the order its entries are given in. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    @Test
    void writesFactsAndContextsAndLeavesOutContextsThatAreNull() {
        assertEquals("{\"facts\":{\"amount\":150000,\"tier\":\"VIP\"}}",
                body(map("amount", 150000, "tier", "VIP"), null));
        assertEquals("{\"facts\":{},\"contexts\":{\"trafficKey\":\"u-1\"}}",
                body(Map.of(), map("trafficKey", "u-1")));
        assertEquals("{\"facts\":{},\"contexts\":{}}", body(Map.of(), Map.of()));
    }

    @Test
    void writesNullAsNullAndKeepsItsKeyAtEveryLevel() {
        List<Object> items = new ArrayList<>(Arrays.asList(null, 1));
        assertEquals("{\"facts\":{\"coupon\":null,\"order\":{\"note\":null,\"items\":[null,1]}}}",
                body(map("coupon", null, "order", map("note", null, "items", items)), null));
    }

    @Test
    void writesEveryTypeTheClientSends() {
        Map<String, Object> facts = map(
                "decimal", new BigDecimal("1.50"),
                "exponent", new BigDecimal("1E+400"),
                "small", new BigDecimal("0.0000001"),
                "integer", new BigInteger("123456789012345678901234567890"),
                "long", Long.MIN_VALUE,
                "int", 7,
                "short", (short) -3,
                "byte", (byte) 9,
                "double", 0.1,
                "float", 100.0f,
                "true", true,
                "false", false,
                "list", new LinkedList<>(List.of("a", "b")));
        assertEquals("{\"facts\":{\"decimal\":1.50,\"exponent\":1E+400,\"small\":1E-7,"
                + "\"integer\":123456789012345678901234567890,\"long\":-9223372036854775808,\"int\":7,"
                + "\"short\":-3,\"byte\":9,\"double\":0.1,\"float\":100,\"true\":true,\"false\":false,"
                + "\"list\":[\"a\",\"b\"]}}", body(facts, null));
    }

    @Test
    void escapesStringsAsJsonStringifyDoes() {
        String text = "q\"b\\s/\b\f\n\r\t" + (char) 0x01 + (char) 0x1f + (char) 0x7f + (char) 0x2028
                + " \uD83D\uDE00 " + (char) 0xd800 + " " + (char) 0xdc00 + "x €";
        String expected = "\"q\\\"b\\\\s/\\b\\f\\n\\r\\t\\u0001\\u001f" + (char) 0x7f + (char) 0x2028
                + " \uD83D\uDE00 \\ud800 \\udc00x €\"";
        assertEquals("{\"facts\":{" + expected + ":" + expected + "}}", body(map(text, text), null));
    }

    @Test
    void writesTheBodyAsUtf8WithNoCharacterLost() {
        // The euro sign takes 3 bytes in UTF-8, the emoji 4, and the unpaired surrogate goes out as an escape.
        byte[] bytes = JsonWriter.body(map("€", "\uD83D\uDE00", "lone", String.valueOf((char) 0xd83d)), null);
        assertArrayEquals("{\"facts\":{\"€\":\"\uD83D\uDE00\",\"lone\":\"\\ud83d\"}}".getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    void writesAnAmountWithMoreThan34SignificantDigitsAndReadsItBack() throws UnreadableJsonException {
        BigDecimal amount = new BigDecimal("12345678901234567890.12345678901234567890");
        assertEquals(40, amount.precision());
        Object read = JsonReader.read(JsonWriter.body(map("amount", amount), null));
        BigDecimal back = (BigDecimal) ((Map<?, ?>) ((Map<?, ?>) read).get("facts")).get("amount");
        assertEquals(amount.unscaledValue(), back.unscaledValue());
        assertEquals(amount.scale(), back.scale());
    }

    /** How many levels a parsed JSON value nests. The outermost brace or bracket is the first. */
    private static int levels(Object value) {
        Iterable<?> children = value instanceof Map<?, ?> map ? map.values() : value instanceof List<?> list ? list : null;
        if (children == null) {
            return 0;
        }
        int deepest = 0;
        for (Object child : children) {
            deepest = Math.max(deepest, levels(child));
        }
        return 1 + deepest;
    }

    /** A value that nests the given number of levels, alternating lists and maps. */
    private static Object nested(int count) {
        Object value = 1;
        for (int level = 0; level < count; level++) {
            value = level % 2 == 0 ? List.of(value) : Map.of("a", value);
        }
        return value;
    }

    @Test
    void writesABodyNestedAsDeepAsLexqAllowsAndRefusesOneLevelMore() throws UnreadableJsonException {
        // A value in the facts starts at the third level, inside the body and the facts.
        byte[] deepest = JsonWriter.body(map("deep", nested(MAX_DEPTH - 2)), map("deep", nested(MAX_DEPTH - 2)));
        assertEquals(MAX_DEPTH, levels(JsonReader.read(deepest)));
        assertTrue(refusal(map("deep", nested(MAX_DEPTH - 1)), null)
                .matches("facts\\.deep\\S* would nest the request body more than " + MAX_DEPTH + " levels deep"));
        assertTrue(refusal(Map.of(), map("deep", nested(MAX_DEPTH - 1)))
                .matches("contexts\\.deep\\S* would nest the request body more than " + MAX_DEPTH + " levels deep"));
        // An empty list counts as a level too.
        Object empty = List.of();
        for (int level = 0; level < MAX_DEPTH - 2; level++) {
            empty = List.of(empty);
        }
        assertTrue(refusal(map("deep", empty), null).endsWith("would nest the request body more than " + MAX_DEPTH + " levels deep"));
    }

    @Test
    void refusesAStructureThatContainsItself() {
        Map<String, Object> order = new HashMap<>();
        order.put("self", order);
        assertEquals("facts.order.self contains itself, which JSON cannot carry", refusal(map("order", order), null));
        List<Object> list = new ArrayList<>();
        list.add(list);
        assertEquals("facts.list[0] contains itself, which JSON cannot carry", refusal(map("list", list), null));
    }

    @Test
    void writesTheSameObjectTwiceWhenItDoesNotContainItself() {
        Map<String, Object> shared = map("tier", "VIP");
        assertEquals("{\"facts\":{\"a\":{\"tier\":\"VIP\"},\"b\":[{\"tier\":\"VIP\"},{\"tier\":\"VIP\"}]}}",
                body(map("a", shared, "b", List.of(shared, shared)), null));
    }

    @Test
    void refusesNumbersJsonCannotCarryAndNamesWhereTheyAre() {
        assertEquals("facts.order.lines[0].amount is NaN, which JSON cannot carry",
                refusal(map("order", map("lines", List.of(map("amount", Double.NaN)))), null));
        assertEquals("facts.rate is Infinity, which JSON cannot carry",
                refusal(map("rate", Double.POSITIVE_INFINITY), null));
        assertEquals("contexts.score is -Infinity, which JSON cannot carry",
                refusal(Map.of(), map("score", Float.NEGATIVE_INFINITY)));
        assertEquals("facts.ratio is NaN, which JSON cannot carry", refusal(map("ratio", Float.NaN), null));
    }

    @Test
    void refusesAMapKeyThatIsNotAString() {
        Map<Object, Object> byNumber = new HashMap<>();
        byNumber.put(1, "one");
        assertEquals("facts.codes has a key that is not a string, which JSON cannot carry",
                refusal(map("codes", byNumber), null));
        Map<Object, Object> withNull = new HashMap<>();
        withNull.put(null, "x");
        assertEquals("facts has a key that is not a string, which JSON cannot carry", refusal(withNull, null));
    }

    @Test
    void refusesEveryCollectionThatIsNotAList() {
        assertEquals("facts.tags has type java.util.HashSet, a collection whose order the client cannot rely on",
                refusal(map("tags", new java.util.HashSet<>(List.of("a"))), null));
        assertEquals("facts.tags has type java.util.TreeSet, a collection whose order the client cannot rely on",
                refusal(map("tags", new TreeSet<>(List.of("a"))), null));
        assertEquals("facts.tags has type java.util.LinkedHashSet, a collection whose order the client cannot rely on",
                refusal(map("tags", new LinkedHashSet<>(List.of("a"))), null));
        assertTrue(refusal(map("tags", Map.of("k", "v").values()), null)
                .endsWith(", a collection whose order the client cannot rely on"));
        assertTrue(refusal(map("tags", Set.of("a")), null)
                .endsWith(", a collection whose order the client cannot rely on"));
    }

    enum Tier { VIP }

    /** A BigDecimal whose text is not a number. */
    static final class OddDecimal extends BigDecimal {
        private static final long serialVersionUID = 1L;

        OddDecimal() {
            super(1);
        }

        @Override
        public String toString() {
            return "not a number";
        }
    }

    @Test
    void refusesEveryOtherTypeAndNamesTheType() {
        assertEquals("facts.at has type java.time.Instant, which the client does not send",
                refusal(map("at", Instant.EPOCH), null));
        assertEquals("facts.tier has type io.lexq.sdk.JsonWriterTest$Tier, which the client does not send",
                refusal(map("tier", Tier.VIP), null));
        assertEquals("facts.grade has type java.lang.Character, which the client does not send",
                refusal(map("grade", 'A'), null));
        assertEquals("facts.ids has type int[], which the client does not send", refusal(map("ids", new int[] {1}), null));
        assertEquals("facts.count has type java.util.concurrent.atomic.AtomicLong, which the client does not send",
                refusal(map("count", new AtomicLong(1)), null));
        assertEquals("facts.maybe has type java.util.Optional, which the client does not send",
                refusal(map("maybe", Optional.of(1)), null));
        assertEquals("facts.amount has type io.lexq.sdk.JsonWriterTest$OddDecimal, which the client does not send",
                refusal(map("amount", new OddDecimal()), null));
        Supplier<String> lambda = () -> "x";
        assertEquals("facts.call has a type the client does not send", refusal(map("call", lambda), null));
    }

    @Test
    void neverPutsAValueInTheMessage() {
        String message = refusal(map("email", "someone@example.com", "tags", new TreeSet<>(List.of("secret-tag"))), null);
        assertFalse(message.contains("someone"));
        assertFalse(message.contains("secret"));
    }

    @Test
    void escapesLineBreaksFormatCharactersAndUnpairedSurrogatesInTheKeyPath() {
        String breaks = "" + (char) 0x0a + (char) 0x0d + (char) 0x85 + (char) 0x2028 + (char) 0x2029;
        assertEquals("facts.k\\u000a\\u000d\\u0085\\u2028\\u2029 b is NaN, which JSON cannot carry",
                refusal(map("k" + breaks + " b", Double.NaN), null));
        // A right-to-left override, a zero width space, a tag letter, and an unpaired surrogate.
        String hidden = "" + (char) 0x202e + (char) 0x200b + new String(Character.toChars(0xe0041)) + (char) 0xd800;
        assertEquals("facts.k\\u202e\\u200b\\udb40\\udc41\\ud800 is NaN, which JSON cannot carry",
                refusal(map("k" + hidden, Double.NaN), null));
        // An emoji is printable, so it stays as it is.
        assertEquals("facts.\uD83D\uDE00 is NaN, which JSON cannot carry", refusal(map("\uD83D\uDE00", Double.NaN), null));
    }

    @Test
    void keepsAKeyPathAsLongAsTheLimitWholeAndCutsALongerOne() {
        String problem = " is NaN, which JSON cannot carry";
        String exact = "x".repeat(MAX_PATH - "facts.".length());
        assertEquals("facts." + exact + problem, refusal(map(exact, Double.NaN), null));
        String longer = exact + "y";
        assertEquals("facts." + exact + "..." + problem, refusal(map(longer, Double.NaN), null));
        String huge = "x".repeat(100_000);
        assertEquals("facts." + huge.substring(0, MAX_PATH - "facts.".length()) + "..." + problem,
                refusal(map(huge, Double.NaN), null));
    }

    @Test
    void neverCutsASurrogatePairInTheKeyPath() {
        // With "facts." in front, the emoji's first code unit falls on the last position the cut
        // keeps, so the cut drops the whole emoji.
        String filler = "x".repeat(MAX_PATH - "facts.".length() - 1);
        String key = filler + "\uD83D\uDE00" + "x".repeat(10);
        assertEquals("facts." + filler + "... is NaN, which JSON cannot carry", refusal(map(key, Double.NaN), null));
    }

    @Test
    void cutsTextAsTheDefaultsSay() {
        assertEquals("abc", Text.cut("abc", 3));
        assertEquals("ab...", Text.cut("abc", 2));
        assertEquals("a...", Text.cut("a\uD83D\uDE00", 2));
        assertEquals("a\uD83D\uDE00", Text.cut("a\uD83D\uDE00", 3));
    }
}
