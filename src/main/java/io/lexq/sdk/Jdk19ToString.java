package io.lexq.sdk;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * The text that {@code Double.toString(double)} and {@code Float.toString(float)} write from JDK 19
 * on, computed without calling them. JDK 19 changed how the two methods pick the digits, and JDK 17
 * writes some values differently, such as {@code 2e23} as {@code 1.9999999999999998E23} instead of
 * {@code 2.0E23}. So the client uses this class before JDK 19, and the JDK's methods from JDK 19 on.
 *
 * <p>It follows the definition in the JDK 19 Javadoc of {@code Double.toString(double)}. A decimal
 * is {@code s} &times; 10<sup>{@code i}</sup> with {@code s} a positive integer that is not a
 * multiple of 10, and its length is the number of digits of {@code s}. Of the decimals that round to
 * the value, it takes those of the smallest length, or those of length 1 or 2 when the smallest
 * length is 1, and picks the one closest to the value, the one with the even {@code s} on a tie. So
 * the text is not always the shortest: {@code Double.MIN_VALUE} is written {@code 4.9E-324}, though
 * {@code 5E-324} reads back as the same value. The arithmetic is exact, in {@code BigDecimal}.
 */
final class Jdk19ToString {
    private Jdk19ToString() {}

    /** The text {@code Double.toString(value)} writes from JDK 19 on. */
    static String of(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        long bits = Double.doubleToRawLongBits(value);
        String sign = bits < 0 ? "-" : "";
        if (Double.isInfinite(value)) {
            return sign + "Infinity";
        }
        if (value == 0) {
            return sign + "0.0";
        }
        int biased = (int) (bits >>> 52) & 0x7ff;
        long fraction = bits & 0xf_ffff_ffff_ffffL;
        long significand = biased == 0 ? fraction : fraction | 1L << 52;
        int exponent = biased == 0 ? -1074 : biased - 1075;
        return sign + text(significand, exponent, fraction == 0 && biased > 1);
    }

    /** The text {@code Float.toString(value)} writes from JDK 19 on. */
    static String of(float value) {
        if (Float.isNaN(value)) {
            return "NaN";
        }
        int bits = Float.floatToRawIntBits(value);
        String sign = bits < 0 ? "-" : "";
        if (Float.isInfinite(value)) {
            return sign + "Infinity";
        }
        if (value == 0) {
            return sign + "0.0";
        }
        int biased = bits >>> 23 & 0xff;
        int fraction = bits & 0x7f_ffff;
        long significand = biased == 0 ? fraction : fraction | 1 << 23;
        int exponent = biased == 0 ? -149 : biased - 150;
        return sign + text(significand, exponent, fraction == 0 && biased > 1);
    }

    /**
     * The text for the positive value {@code significand} &times; 2<sup>{@code exponent}</sup>.
     * When {@code narrowBelow} is true, the value is a power of two above the smallest normal one,
     * so the next value below it is half as far away as the next value above it.
     */
    private static String text(long significand, int exponent, boolean narrowBelow) {
        BigDecimal value = new BigDecimal(significand).multiply(powerOfTwo(exponent));
        // A decimal rounds to the value when it lies between the midpoints to the two neighboring
        // values. A decimal on a midpoint rounds to the neighbor with the even significand, so the
        // midpoints belong to this value exactly when its significand is even.
        BigDecimal halfStepAbove = powerOfTwo(exponent - 1);
        BigDecimal low = value.subtract(narrowBelow ? powerOfTwo(exponent - 2) : halfStepAbove);
        Bounds bounds = new Bounds(low, value.add(halfStepAbove), (significand & 1) == 0);

        // The width of the bounds is at least ten times 10^k here, so a multiple of 10^k lies inside.
        BigDecimal width = bounds.high.subtract(bounds.low);
        int k = width.precision() - width.scale() - 2;
        BigInteger multiple = bounds.smallestMultiple(k);
        // A multiple of 10^(k + 1) is a multiple of 10^k too, so once a power holds none, larger
        // powers hold none either. The largest power with a multiple gives the smallest length.
        for (BigInteger next = bounds.smallestMultiple(k + 1); next != null; next = bounds.smallestMultiple(k + 1)) {
            multiple = next;
            k++;
        }
        BigDecimal pick;
        if (multiple.compareTo(BigInteger.TEN) >= 0) {
            // Length 2 or more: the decimals of that length are the multiples of 10^k inside.
            pick = closest(value, bounds, k, null);
        } else {
            // Length 1: the decimals of length 1 or 2 are the multiples of 10^(k - 1) inside, and,
            // below 10^k, the multiples of 10^(k - 2).
            pick = closest(value, bounds, k - 1, null);
            if (value.compareTo(BigDecimal.ONE.scaleByPowerOfTen(k)) < 0) {
                pick = closest(value, bounds, k - 2, pick);
            }
        }
        return format(pick.stripTrailingZeros());
    }

    /**
     * Of the two multiples of 10^{@code power} next to the value, and {@code best} unless it is
     * null, the one inside the bounds that is closest to the value. Of two equally close, it takes
     * the one whose significand is even.
     */
    private static BigDecimal closest(BigDecimal value, Bounds bounds, int power, BigDecimal best) {
        BigDecimal scaled = value.scaleByPowerOfTen(-power);
        BigDecimal floor = scaled.setScale(0, RoundingMode.FLOOR).scaleByPowerOfTen(power);
        BigDecimal ceiling = scaled.setScale(0, RoundingMode.CEILING).scaleByPowerOfTen(power);
        for (BigDecimal candidate : new BigDecimal[] {floor, ceiling}) {
            if (bounds.contains(candidate) && (best == null || closer(value, candidate, best))) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean closer(BigDecimal value, BigDecimal candidate, BigDecimal best) {
        int order = value.subtract(candidate).abs().compareTo(value.subtract(best).abs());
        return order < 0
                || order == 0 && !candidate.stripTrailingZeros().unscaledValue().testBit(0)
                        && best.stripTrailingZeros().unscaledValue().testBit(0);
    }

    /** The decimal formatted as the JDK 19 Javadoc of {@code Double.toString(double)} says. */
    private static String format(BigDecimal decimal) {
        String digits = decimal.unscaledValue().toString();
        int length = digits.length();
        int exponent = -decimal.scale();
        int e = length + exponent - 1;
        StringBuilder out = new StringBuilder(length + 8);
        if (e >= -3 && e < 0) {
            out.append("0.");
            out.append("0".repeat(-(length + exponent)));
            out.append(digits);
        } else if (e >= 0 && e < 7) {
            if (exponent >= 0) {
                out.append(digits).append("0".repeat(exponent)).append(".0");
            } else {
                out.append(digits, 0, length + exponent).append('.').append(digits, length + exponent, length);
            }
        } else {
            out.append(digits.charAt(0)).append('.');
            out.append(length == 1 ? "0" : digits.substring(1));
            out.append('E').append(e);
        }
        return out.toString();
    }

    /** 2<sup>{@code exponent}</sup>, exactly. */
    private static BigDecimal powerOfTwo(int exponent) {
        return exponent >= 0
                ? new BigDecimal(BigInteger.ONE.shiftLeft(exponent))
                : new BigDecimal(BigInteger.valueOf(5).pow(-exponent), -exponent);
    }

    /** The decimals that round to a value: those between low and high, and the two bounds when closed. */
    private record Bounds(BigDecimal low, BigDecimal high, boolean closed) {
        boolean contains(BigDecimal decimal) {
            int fromLow = decimal.compareTo(low);
            int fromHigh = decimal.compareTo(high);
            return closed ? fromLow >= 0 && fromHigh <= 0 : fromLow > 0 && fromHigh < 0;
        }

        /** The smallest integer {@code s} with {@code s} &times; 10^{@code power} inside, or null when there is none. */
        BigInteger smallestMultiple(int power) {
            BigDecimal scaledLow = low.scaleByPowerOfTen(-power);
            BigDecimal s = scaledLow.setScale(0, RoundingMode.CEILING);
            if (!closed && s.compareTo(scaledLow) == 0) {
                s = s.add(BigDecimal.ONE);
            }
            return contains(s.scaleByPowerOfTen(power)) ? s.toBigInteger() : null;
        }
    }
}
