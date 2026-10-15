package io.agentic.shortener.observability;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/** Wires logs into OpenTelemetry and exposes the trace id to clients (OBS-002, OBS-003). */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** The logback appender is declared in logback-spring.xml; it needs the SDK installed to export. */
    @Bean
    InitializingBean installOpenTelemetryLogAppender(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }

    /** Prometheus scrapes every 5 s; tracing those requests would bury real traces in noise. */
    @Bean
    ObservationPredicate skipActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith("/actuator"));
    }

    /** Returns the trace id so a caller can find the request's trace in OpenObserve. */
    @Bean
    OncePerRequestFilter traceIdResponseHeader(Tracer tracer) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                var span = tracer.currentSpan();
                if (span != null) {
                    response.setHeader(TRACE_ID_HEADER, span.context().traceId());
                }
                chain.doFilter(request, response);
            }
        };
    }
}
