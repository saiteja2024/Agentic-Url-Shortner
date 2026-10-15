package io.agentic.shortener.domain;

/** A request field failed a business or safety rule; maps to 400 with an {@code errors[]} entry. */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
