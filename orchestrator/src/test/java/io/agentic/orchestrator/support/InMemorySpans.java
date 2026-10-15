package io.agentic.orchestrator.support;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Captures finished spans: Boot adds every SpanExporter bean to the tracer provider. */
@TestConfiguration(proxyBeanMethods = false)
public class InMemorySpans {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }
}
