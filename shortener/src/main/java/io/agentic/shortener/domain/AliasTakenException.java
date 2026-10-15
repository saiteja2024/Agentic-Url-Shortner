package io.agentic.shortener.domain;

/** The requested alias is already in use (SHORT-003); maps to 409. */
public class AliasTakenException extends RuntimeException {

    public AliasTakenException(String alias) {
        super("Alias '" + alias + "' is already taken.");
    }
}
