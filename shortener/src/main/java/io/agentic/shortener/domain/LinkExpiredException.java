package io.agentic.shortener.domain;

/** The link exists but has expired (SHORT-009); maps to 410. */
public class LinkExpiredException extends RuntimeException {

    public LinkExpiredException(String code) {
        super("Short URL '" + code + "' has expired.");
    }
}
