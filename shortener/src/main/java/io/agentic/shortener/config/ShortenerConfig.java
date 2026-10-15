package io.agentic.shortener.config;

import io.agentic.shortener.code.Base62CodeGenerator;
import io.agentic.shortener.code.CodeGenerator;
import io.agentic.shortener.validation.DnsHostResolver;
import io.agentic.shortener.validation.HostResolver;
import io.agentic.shortener.validation.UrlSafetyValidator;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortenerProperties.class)
class ShortenerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CodeGenerator codeGenerator(ShortenerProperties properties) {
        return new Base62CodeGenerator(properties.codeLength(), new SecureRandom());
    }

    @Bean
    HostResolver hostResolver() {
        return new DnsHostResolver();
    }

    @Bean
    UrlSafetyValidator urlSafetyValidator(HostResolver hostResolver) {
        return new UrlSafetyValidator(hostResolver);
    }
}
