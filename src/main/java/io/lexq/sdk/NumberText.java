package io.lexq.sdk;

import java.math.BigDecimal;

/**
 * The text the client writes for a {@code double} or a {@code float}. It starts from the text that
 * {@code Double.toString} or {@code Float.toString} writes from JDK 19 on, drops the trailing zeros
 * after its decimal point and before any {@code E}, and writes the resulting number as
 * {@code BigDecimal.toString()} does. So {@code 100.0} goes out as {@code 100}, {@code 1.0E-4} as
 * {@code 0.0001}, and {@code 1.0E7} as {@code 1E+7}.
 */
final class NumberText {
    private NumberText() {}

    /** From JDK 19 on, the JDK's methods write the starting text. Before that, {@link Jdk19ToString} does. */
    private static final boolean JDK_WRITES_IT = Runtime.version().feature() >= 19;

    /** The text for a finite {@code double}. */
    static String of(double value) {
        return decimal(JDK_WRITES_IT ? Double.toString(value) : Jdk19ToString.of(value));
    }

    /** The text for a finite {@code float}. */
    static String of(float value) {
        return decimal(JDK_WRITES_IT ? Float.toString(value) : Jdk19ToString.of(value));
    }

    /**
     * The number that {@code text}, a finite value as {@code Double.toString} writes it, holds once
     * the trailing zeros after its decimal point are dropped, written as {@code BigDecimal.toString()}
     * does. A negative zero becomes {@code 0}, because {@code BigDecimal} has no negative zero.
     */
    static String decimal(String text) {
        int exponent = text.indexOf('E');
        int end = exponent < 0 ? text.length() : exponent;
        int point = text.indexOf('.');
        int cut = end;
        if (point >= 0) {
            while (cut > point + 1 && text.charAt(cut - 1) == '0') {
                cut--;
            }
            if (cut == point + 1) {
                cut = point;
            }
        }
        return new BigDecimal(text.substring(0, cut) + text.substring(end)).toString();
    }
}
