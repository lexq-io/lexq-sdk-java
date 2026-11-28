package io.lexq.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonReaderTest {

    private static Object read(String text) throws UnreadableJsonException {
        return JsonReader.read(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String refusal(String text) {
        String label = text.length() > 60 ? text.substring(0, 60) + "..." : text;
        return assertThrows(UnreadableJsonException.class, () -> read(text), label).getMessage();
    }

    private static String refusal(byte[] bytes) {
        return assertThrows(UnreadableJsonException.class, () -> JsonReader.read(bytes)).getMessage();
    }

    @Test
    void readsEveryKindOfValueIntoTheTypesTheClientUses() throws UnreadableJsonException {
        Object read = read(" {\"a\":1.10,\"b\":\"1.10\",\"c\":true,\"d\":false,\"e\":null,\"f\":[1e2,{\"g\":-0}],\"h\":{},\"i\":[]} ");
        Map<?, ?> object = assertInstanceOf(LinkedHashMap.class, read);
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "h", "i"), new ArrayList<>(object.keySet()));
        assertEquals(new BigDecimal("1.10"), object.get("a"));
        assertEquals("1.10", object.get("b"));
        assertEquals(Boolean.TRUE, object.get("c"));
        assertEquals(Boolean.FALSE, object.get("d"));
        assertNull(object.get("e"));
        List<?> list = assertInstanceOf(ArrayList.class, object.get("f"));
        assertEquals(new BigDecimal("1e2"), list.get(0));
        assertEquals(new BigDecimal("0"), ((Map<?, ?>) list.get(1)).get("g"));
        assertEquals(Map.of(), object.get("h"));
        assertEquals(List.of(), object.get("i"));
        assertEquals("text", read("\"text\""));
        assertEquals(new BigDecimal("7"), read("7"));
    }

    @Test
    void keepsTheDigitsAndTheScaleOfEveryNumber() throws UnreadableJsonException {
        BigDecimal scaled = (BigDecimal) read("1.50");
        assertEquals(new BigInteger("150"), scaled.unscaledValue());
        assertEquals(2, scaled.scale());
        BigDecimal exponent = (BigDecimal) read("1E+400");
        assertEquals("1E+400", exponent.toString());
        BigDecimal small = (BigDecimal) read("0.0000001");
        assertEquals(7, small.scale());
        String digits = "12345678901234567890.12345678901234567890";
        BigDecimal amount = (BigDecimal) read(digits);
        assertEquals(40, amount.precision());
        assertEquals(digits, amount.toString());
    }

    @Test
    void readsANumberAsLongAsTheLimitAndRefusesALongerOne() throws UnreadableJsonException {
        String longest = "1" + "0".repeat(JsonReader.MAX_NUMBER_LENGTH - 1);
        BigDecimal number = (BigDecimal) ((List<?>) read("[" + longest + "]")).get(0);
        assertEquals(JsonReader.MAX_NUMBER_LENGTH, number.precision());
        assertEquals("The body cannot be read as JSON: a number longer than " + JsonReader.MAX_NUMBER_LENGTH
                + " characters at offset 1.", refusal("[" + longest + "0]"));
        assertEquals("The body cannot be read as JSON: a number longer than " + JsonReader.MAX_NUMBER_LENGTH
                + " characters at offset 0.", refusal("-0." + "0".repeat(JsonReader.MAX_NUMBER_LENGTH)));
    }

    @Test
    void refusesAnExponentOrScaleOutsideTheRangeOfAnInt() throws UnreadableJsonException {
        assertEquals("1E+2147483647", read("1E+2147483647").toString());
        assertEquals("1E-2147483647", read("1E-2147483647").toString());
        assertEquals("1E+5", read("1E+00000000000000000005").toString());
        assertEquals("1E-2147483647", read("1E-0000000000002147483647").toString());
        // JDK 21 and 25 read the first five, because the scale fits in an int. JDK 17 does not.
        // No JDK reads the rest: the exponent has too many digits, or the scale does not fit.
        for (String text : List.of("1E+2147483648", "10E+2147483648", "1.5E+2147483648", "0E+2147483648",
                "1E+0000000000002147483648", "1E+99999999999999999999", "1E-2147483649", "1E-2147483648",
                "1.5E-2147483647")) {
            assertEquals("The body cannot be read as JSON: a number whose exponent is out of range at offset 0.",
                    refusal(text));
        }
    }

    @Test
    void readsEscapesAndCharactersOutsideAscii() throws UnreadableJsonException {
        assertEquals("q\"b\\s/\b\f\n\r\tA", read("\"q\\\"b\\\\s\\/\\b\\f\\n\\r\\t\\u0041\""));
        assertEquals("\uD83D\uDE00", read("\"\\ud83d\\uDE00\""));
        assertEquals("\uD83D\uDE00 €", read("\"\uD83D\uDE00 €\""));
        // An escaped unpaired surrogate is valid JSON, and the string keeps it.
        assertEquals(String.valueOf((char) 0xd800), read("\"\\ud800\""));
        assertEquals(String.valueOf((char) 0x7f), read("\"" + (char) 0x7f + "\""));
    }

    @Test
    void keepsTheLastValueOfARepeatedKey() throws UnreadableJsonException {
        Map<?, ?> object = (Map<?, ?>) read("{\"a\":1,\"b\":2,\"a\":3}");
        assertEquals(List.of("a", "b"), new ArrayList<>(object.keySet()));
        assertEquals(new BigDecimal("3"), object.get("a"));
    }

    @Test
    void readsKeysThatOtherParsersTreatAsInstructionsAsOrdinaryKeys() throws UnreadableJsonException {
        Map<?, ?> object = (Map<?, ?>) read(
                "{\"@class\":\"java.lang.Runtime\",\"@type\":{\"x\":1},\"__proto__\":{\"polluted\":true},\"constructor\":[]}");
        assertEquals("java.lang.Runtime", object.get("@class"));
        assertInstanceOf(LinkedHashMap.class, object.get("@type"));
        assertInstanceOf(LinkedHashMap.class, object.get("__proto__"));
        assertInstanceOf(ArrayList.class, object.get("constructor"));
    }

    @Test
    void readsAnyNestingWithoutOverflowingTheStack() throws UnreadableJsonException {
        int depth = 200_000;
        Object value = read("[".repeat(depth) + "]".repeat(depth));
        int levels = 0;
        while (value instanceof List<?> list) {
            levels++;
            value = list.isEmpty() ? null : list.get(0);
        }
        assertEquals(depth, levels);
    }

    @Test
    void refusesTextThatIsNotJson() {
        String[] texts = {
            "", " ", "{", "[", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{\"a\":}", "{a:1}", "[1 2]", "{} {}",
            "01", "1.", ".5", "+1", "1e", "1e+", "-", "- 1", "NaN", "Infinity", "-Infinity", "'a'", "tru",
            "nul", "True", "\"unterminated", "\"\\x\"", "\"\\u12G4\"", "\"\\u123\"", "\"\\u\u0663\u0663\u0663\u0663\"",
            "\"a" + (char) 0x01 + "\"", "\"a\nb\"", "\uFEFF{}", "{\"a\":1}x", "[1]]",
        };
        for (String text : texts) {
            refusal(text);
        }
    }

    @Test
    void refusesABodyThatIsNotUtf8() {
        for (byte[] bytes : new byte[][] {
            {(byte) 0xc3, (byte) 0x28},
            // A surrogate encoded on its own, as CESU-8 writes it.
            {'"', (byte) 0xed, (byte) 0xa0, (byte) 0x80, '"'},
            // An overlong encoding of "/".
            {'"', (byte) 0xc0, (byte) 0xaf, '"'},
            {'"', (byte) 0xff, '"'},
        }) {
            assertEquals("The body is not UTF-8.", refusal(bytes));
        }
    }

    @Test
    void namesWhereTheProblemIsButNeverQuotesTheBody() {
        String message = refusal("{\"secret\": tru}");
        assertEquals("The body cannot be read as JSON: unexpected character at offset 11.", message);
        assertFalse(message.contains("secret"));
    }
}
