package io.agentic.shortener.domain;

/** Click count for one referrer; direct visits use {@link #DIRECT}. */
public record ReferrerClicks(String referrer, long clicks) {

    public static final String DIRECT = "(direct)";
}
