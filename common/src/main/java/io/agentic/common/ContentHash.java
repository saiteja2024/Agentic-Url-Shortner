package io.agentic.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** SHA-256 content hashing, used to fingerprint artifacts for lineage and change detection. */
public final class ContentHash {

    private static final String ALGORITHM = "SHA-256";

    private ContentHash() {}

    /** Returns the lowercase hex SHA-256 digest of the UTF-8 bytes of {@code content}. */
    public static String sha256Hex(String content) {
        Objects.requireNonNull(content, "content");
        byte[] hash = digestFor(ALGORITHM).digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    static MessageDigest digestFor(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }
}
