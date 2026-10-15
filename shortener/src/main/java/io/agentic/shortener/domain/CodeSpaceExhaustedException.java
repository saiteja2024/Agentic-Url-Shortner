package io.agentic.shortener.domain;

/** Every generated code collided (SHORT-002); maps to 503. */
public class CodeSpaceExhaustedException extends RuntimeException {

    public CodeSpaceExhaustedException(int attempts) {
        super("Could not allocate a unique code after " + attempts + " attempts.");
    }
}
