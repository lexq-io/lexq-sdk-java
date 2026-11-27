package io.lexq.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * The text the client writes for a {@code double} or a {@code float}, and {@link Jdk19ToString},
 * which writes the starting text on JDK 17 and 18.
 */
class NumberTextTest {
    /** How many random doubles a test draws, and as many floats. Each run draws new ones, and a failure names its seed. */
    private static final int RANDOM_VALUES = 300_000;

    @Test
    void writesTheExamplesInContract() {
        assertEquals("0.1", NumberText.of(0.1));
        assertEquals("100", NumberText.of(100.0));
        assertEquals("0.0001", NumberText.of(0.0001));
        assertEquals("1E-7", NumberText.of(0.0000001));
        assertEquals("1E+7", NumberText.of(10000000.0));
        assertEquals("2E+23", NumberText.of(2e23));
        assertEquals("0.1", NumberText.of(0.1f));
        assertEquals("1E+10", NumberText.of(1.0E10f));
    }

    @Test
    void writesNegativeZeroAsZero() {
        // BigDecimal has no negative zero, so -0.0 goes out as 0.
        assertEquals("0", NumberText.of(-0.0));
        assertEquals("0", NumberText.of(-0.0f));
        assertEquals("0", NumberText.of(0.0));
    }

    @Test
    void keepsTheJdkTextThatIsNotTheShortest() {
        // 5E-324 reads back as Double.MIN_VALUE too, but the JDK 19 rule picks 4.9E-324.
        assertEquals("4.9E-324", Jdk19ToString.of(Double.MIN_VALUE));
        assertEquals("4.9E-324", NumberText.of(Double.MIN_VALUE));
        assertEquals("1.4E-45", Jdk19ToString.of(Float.MIN_VALUE));
        assertEquals("1.7976931348623157E+308", NumberText.of(Double.MAX_VALUE));
        assertEquals("-1.2345E+30", NumberText.of(-1.2345e30));
    }

    /**
     * Values whose text JDK 17 writes differently, with the text {@code Double.toString} and
     * {@code Float.toString} wrote for them on JDK 25.0.5. These run on every JDK, JDK 17 included.
     */
    @Test
    void writesWhatJdk19WritesWhereEarlierJdksDiffer() {
        Object[][] doubles = {
            // Twice Double.MIN_VALUE, which JDK 17 writes 1.0E-323.
            {0x2L, "9.9E-324"},
            {0x44c52d02c7e14af6L, "2.0E23"},
            {0x44b52d02c7e14af6L, "1.0E23"},
            {0x447c7e83209e90b2L, "8.41E21"},
            {0xc3ccb1cdce11b6e0L, "-4.135319977767846E18"},
            {0x437c977c62b47c26L, "1.2876575737012285E17"},
            {0x4375272b73829a07L, "9.52646733750355E16"},
            {0xc3bffbb00586e6d3L, "-2.30462917211474E18"},
            {0xc53484ce2ffa88fdL, "-2.4805672254536845E25"},
            {0x436fdd1409526162L, "7.175041922806862E16"},
            {0xc3d4e04d943aea24L, "-6.01715029832267E18"},
            {0x43b12c80dcf2fbc9L, "1.2375056840777915E18"},
        };
        for (Object[] row : doubles) {
            double value = Double.longBitsToDouble((long) row[0]);
            assertEquals(row[1], Jdk19ToString.of(value), () -> Long.toHexString((long) row[0]));
        }
        Object[][] floats = {
            {0xce263b1d, "-6.97223E8"},
            {0xd5e5d07a, "-3.1585445E13"},
            {0x550aacda, "9.529687E12"},
            {0x5a8258c1, "1.8344666E16"},
            {0xcdb1382d, "-3.716561E8"},
            {0x4dcf6a60, "4.349819E8"},
            {0xd04a0c96, "-1.355929E10"},
            {0x5ed59d0f, "7.696238E18"},
            {0x4f4289ac, "3.2638024E9"},
            {0xd9ae5e70, "-6.13506E15"},
        };
        for (Object[] row : floats) {
            float value = Float.intBitsToFloat((int) row[0]);
            assertEquals(row[1], Jdk19ToString.of(value), () -> Integer.toHexString((int) row[0]));
        }
        assertEquals("2E+23", NumberText.of(Double.longBitsToDouble(0x44c52d02c7e14af6L)));
        assertEquals("-6.97223E+8", NumberText.of(Float.intBitsToFloat(0xce263b1d)));
    }

    @Test
    void writesTheSpecialValuesAsTheJdkDoes() {
        assertEquals("NaN", Jdk19ToString.of(Double.NaN));
        assertEquals("Infinity", Jdk19ToString.of(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", Jdk19ToString.of(Float.NEGATIVE_INFINITY));
        assertEquals("-0.0", Jdk19ToString.of(-0.0));
        assertEquals("0.0", Jdk19ToString.of(0.0f));
    }

    /** From JDK 19 on, the client calls the JDK's methods, and {@link Jdk19ToString} must write the same text. */
    @Test
    void matchesTheJdkMethodsFromJdk19On() {
        assumeTrue(Runtime.version().feature() >= 19, "Double.toString writes other text before JDK 19");
        long seed = ThreadLocalRandom.current().nextLong();
        for (double value : structuredDoubles()) {
            assertEquals(Double.toString(value), Jdk19ToString.of(value), () -> describe(value, -1));
        }
        for (float value : structuredFloats()) {
            assertEquals(Float.toString(value), Jdk19ToString.of(value), () -> describe(value, -1));
        }
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < RANDOM_VALUES; i++) {
            double value = Double.longBitsToDouble(random.nextLong());
            assertEquals(Double.toString(value), Jdk19ToString.of(value), () -> describe(value, seed));
            float single = Float.intBitsToFloat(random.nextInt());
            assertEquals(Float.toString(single), Jdk19ToString.of(single), () -> describe(single, seed));
        }
    }

    /** On every JDK, the text the client writes reads back as the same value. */
    @Test
    void writesTextThatReadsBackAsTheSameValue() {
        long seed = ThreadLocalRandom.current().nextLong();
        for (double value : structuredDoubles()) {
            readsBack(value, -1);
        }
        for (float value : structuredFloats()) {
            readsBack(value, -1);
        }
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < RANDOM_VALUES; i++) {
            readsBack(Double.longBitsToDouble(random.nextLong()), seed);
            readsBack(Float.intBitsToFloat(random.nextInt()), seed);
        }
    }

    private static void readsBack(double value, long seed) {
        if (!Double.isFinite(value)) {
            return;
        }
        String own = Jdk19ToString.of(value);
        assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(Double.parseDouble(own)),
                () -> describe(value, seed));
        // The text the client writes on this JDK is Jdk19ToString's text with the same zeros dropped.
        String sent = NumberText.of(value);
        assertEquals(NumberText.decimal(own), sent, () -> describe(value, seed));
        assertEquals(value == 0 ? 0.0 : value, Double.parseDouble(sent), () -> describe(value, seed));
    }

    private static void readsBack(float value, long seed) {
        if (!Float.isFinite(value)) {
            return;
        }
        String own = Jdk19ToString.of(value);
        assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits(Float.parseFloat(own)),
                () -> describe(value, seed));
        String sent = NumberText.of(value);
        assertEquals(NumberText.decimal(own), sent, () -> describe(value, seed));
        assertEquals(value == 0 ? 0.0f : value, Float.parseFloat(sent), () -> describe(value, seed));
    }

    /**
     * Powers of two and one-digit multiples of powers of ten, each with its neighbors, the smallest
     * subnormal values, whole numbers, and amounts with two decimal places.
     */
    private static List<Double> structuredDoubles() {
        List<Double> values = new ArrayList<>();
        for (int e = -1074; e <= 1023; e++) {
            double power = Math.scalb(1.0, e);
            values.add(power);
            values.add(Math.nextUp(power));
            values.add(Math.nextDown(power));
        }
        for (int e = -324; e <= 308; e++) {
            for (int m = 1; m <= 9; m++) {
                double value = Double.parseDouble(m + "e" + e);
                values.add(value);
                values.add(Math.nextUp(value));
                values.add(Math.nextDown(value));
            }
        }
        for (long c = 1; c <= 10_000; c++) {
            values.add(Double.longBitsToDouble(c));
        }
        for (int n = 0; n <= 100_000; n++) {
            values.add((double) n);
            values.add(n / 100.0);
        }
        values.add(Double.MAX_VALUE);
        values.add(Double.MIN_NORMAL);
        values.add(-0.0);
        return values;
    }

    private static List<Float> structuredFloats() {
        List<Float> values = new ArrayList<>();
        for (int e = -149; e <= 127; e++) {
            float power = Math.scalb(1.0f, e);
            values.add(power);
            values.add(Math.nextUp(power));
            values.add(Math.nextDown(power));
        }
        for (int e = -45; e <= 38; e++) {
            for (int m = 1; m <= 9; m++) {
                float value = Float.parseFloat(m + "e" + e);
                values.add(value);
                values.add(Math.nextUp(value));
                values.add(Math.nextDown(value));
            }
        }
        for (int c = 1; c <= 10_000; c++) {
            values.add(Float.intBitsToFloat(c));
        }
        for (int n = 0; n <= 100_000; n++) {
            values.add((float) n);
            values.add(n / 100.0f);
        }
        values.add(Float.MAX_VALUE);
        values.add(Float.MIN_NORMAL);
        return values;
    }

    private static String describe(double value, long seed) {
        return "double with bits 0x" + Long.toHexString(Double.doubleToRawLongBits(value))
                + (seed == -1 ? "" : ", random seed " + seed);
    }

    private static String describe(float value, long seed) {
        return "float with bits 0x" + Integer.toHexString(Float.floatToRawIntBits(value))
                + (seed == -1 ? "" : ", random seed " + seed);
    }
}
