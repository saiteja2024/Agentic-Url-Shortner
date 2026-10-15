package io.agentic.common;

/** MDC keys shared by both services so JSON logs correlate with traces. */
public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String RUN_ID = "runId";
    public static final String NODE_ID = "nodeId";

    private MdcKeys() {}
}
