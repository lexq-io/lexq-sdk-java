package io.lexq.sdk;

/**
 * Text the client did not write, made fit for one line of a log. A line break, such as a line feed
 * or a line separator, would start a new line, and another control character, a format character
 * such as a right-to-left override (U+202E), or an unpaired surrogate would change how the line reads.
 */
final class Text {
    private Text() {}

    /**
     * The text with each control character (Unicode {@code Cc}), format character ({@code Cf}),
     * unpaired surrogate, and line or paragraph separator ({@code Zl}, {@code Zp}) written as
     * {@code \}{@code uXXXX} escapes, one for each UTF-16 code unit.
     */
    static String escaped(String text) {
        StringBuilder out = null;
        int done = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int next = i + Character.charCount(codePoint);
            if (unprintable(codePoint)) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16);
                }
                out.append(text, done, i);
                for (int unit = i; unit < next; unit++) {
                    escape(out, text.charAt(unit));
                }
                done = next;
            }
            i = next;
        }
        return out == null ? text : out.append(text, done, text.length()).toString();
    }

    /**
     * The text cut to at most {@code maxLength} UTF-16 code units, with {@code ...} where it was
     * cut. A cut never splits a surrogate pair, and the {@code ...} is not counted.
     */
    static String cut(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end) + "...";
    }

    /** Appends the {@code \}{@code uXXXX} escape of one UTF-16 code unit, in lower case. */
    static void escape(StringBuilder out, char unit) {
        out.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            out.append(Character.forDigit(unit >> shift & 0xf, 16));
        }
    }

    private static boolean unprintable(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }
}
