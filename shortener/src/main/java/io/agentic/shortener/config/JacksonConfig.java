package io.agentic.shortener.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strict JSON scalars: a boolean or number is never silently turned into a String field
 * ({@code "alias": true} would otherwise become the alias "true"). Bean Validation can't see the
 * original token type after coercion, so this has to be enforced at deserialization (SHORT-015).
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictTextualCoercion() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
