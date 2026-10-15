package dev.linkkit;

import java.time.Instant;

/** A short link. */
public record Link(String code, String url, Instant createdAt) {}
