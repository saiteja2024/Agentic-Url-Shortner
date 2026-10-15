package io.agentic.shortener.code;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Random Base62 codes (SHORT-001). With a {@link java.security.SecureRandom} source, codes are
 * unguessable, so links can't be enumerated by walking a sequence.
 */
public final class Base62CodeGenerator implements CodeGenerator {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final int length;
    private final RandomGenerator random;

    public Base62CodeGenerator(int length, RandomGenerator random) {
        if (length < 3 || length > 32) {
            throw new IllegalArgumentException("code length must be 3..32, was " + length);
        }
        this.length = length;
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String next() {
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
