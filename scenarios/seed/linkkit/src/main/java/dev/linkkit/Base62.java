package dev.linkkit;

/** Encodes non-negative numbers as Base62 strings. */
public final class Base62 {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Base62() {}

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        if (value == 0) {
            return "0";
        }
        StringBuilder out = new StringBuilder();
        while (value > 0) {
            out.append(ALPHABET.charAt((int) (value % 62)));
            value /= 62;
        }
        return out.reverse().toString();
    }
}
