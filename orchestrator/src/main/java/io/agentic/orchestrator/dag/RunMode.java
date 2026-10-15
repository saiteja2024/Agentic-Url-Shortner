package io.agentic.orchestrator.dag;

import java.util.Locale;

/** Greenfield builds something new; brownfield changes existing code (enables impact analysis). */
public enum RunMode {
    GREENFIELD,
    BROWNFIELD;

    /** Whether a stage's {@code when:} condition (e.g. "brownfield") applies to this run. */
    public boolean matches(String when) {
        return name().equals(when.trim().toUpperCase(Locale.ROOT));
    }
}
