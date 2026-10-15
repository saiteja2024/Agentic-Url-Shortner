package io.agentic.shortener.validation;

import io.agentic.shortener.domain.InvalidFieldException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * SSRF and abuse guardrails for target URLs (SHORT-011 … SHORT-014). Rejects anything that is not an
 * absolute http(s) URL to a public address. Hostnames are resolved once and rejected if any address
 * is blocked; names that don't resolve are accepted (assumption A3: the shortener never fetches them).
 */
public final class UrlSafetyValidator {

    private static final String FIELD = "url";
    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Set<String> METADATA_HOSTS = Set.of("metadata.google.internal", "metadata.azure.com");

    private final HostResolver resolver;

    public UrlSafetyValidator(HostResolver resolver) {
        this.resolver = resolver;
    }

    /** @throws InvalidFieldException naming the {@code url} field if the target is not allowed */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw reject("must not be blank");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw reject("is not a valid URL");
        }
        if (!uri.isAbsolute() || !SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw reject("must be an absolute http or https URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw reject("must not contain credentials");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw reject("must contain a valid host");
        }
        checkHost(normalise(uri.getHost()));
    }

    private void checkHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw reject(AddressPolicy.Violation.LOOPBACK.message());
        }
        if (METADATA_HOSTS.contains(host)) {
            throw reject(AddressPolicy.Violation.METADATA.message());
        }
        Optional<InetAddress> literal;
        try {
            literal = IpLiterals.parse(host);
        } catch (IllegalArgumentException e) {
            throw reject("host is not a valid IP address");
        }
        List<InetAddress> addresses = literal.map(List::of).orElseGet(() -> resolve(host));
        for (InetAddress address : addresses) {
            AddressPolicy.check(address).ifPresent(v -> {
                throw reject(v.message());
            });
        }
    }

    private List<InetAddress> resolve(String host) {
        try {
            return resolver.resolve(host);
        } catch (UnknownHostException e) {
            return List.of();
        }
    }

    private static String normalise(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    private static InvalidFieldException reject(String reason) {
        return new InvalidFieldException(FIELD, reason);
    }
}
