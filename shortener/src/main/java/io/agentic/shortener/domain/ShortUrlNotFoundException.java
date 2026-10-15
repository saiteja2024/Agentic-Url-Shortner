package io.agentic.shortener.domain;

/** No link with this code (SHORT-008); maps to 404. */
public class ShortUrlNotFoundException extends RuntimeException {

    public ShortUrlNotFoundException(String code) {
        super("No short URL with code '" + code + "'.");
    }
}
