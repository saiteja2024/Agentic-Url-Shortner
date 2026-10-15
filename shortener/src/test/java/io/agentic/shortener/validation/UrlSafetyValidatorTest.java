package io.agentic.shortener.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentic.shortener.domain.InvalidFieldException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlSafetyValidatorTest {

    /** Deterministic DNS: only these names resolve; everything else is NXDOMAIN. */
    private static final HostResolver FAKE_DNS = host -> {
        Map<String, List<String>> zone = Map.of(
                "www.example.org", List.of("93.184.215.14"),
                "dual.example.org", List.of("93.184.215.14", "10.0.0.7"),
                "rebind.example.org", List.of("127.0.0.1"),
                "internal.example.org", List.of("192.168.1.20"),
                "v6private.example.org", List.of("fd12:3456::1"),
                "imds.example.org", List.of("169.254.169.254"));
        List<String> ips = zone.get(host);
        if (ips == null) {
            throw new UnknownHostException(host);
        }
        return ips.stream().map(UrlSafetyValidatorTest::literal).toList();
    };

    private final UrlSafetyValidator validator = new UrlSafetyValidator(FAKE_DNS);

    @ParameterizedTest
    @Tag("SHORT-001")
    @DisplayName("SHORT-001: accepts public http(s) URLs")
    @ValueSource(
            strings = {
                "https://www.example.org/docs?ref=newsletter#top",
                "http://www.example.org",
                "HTTPS://WWW.EXAMPLE.ORG/Path",
                "https://93.184.215.14/",
                "https://[2606:2800:220:1:248:1893:25c8:1946]/",
                "https://unresolvable.example.net/" // A3: unresolvable hosts are accepted
            })
    void acceptsPublicUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @Tag("SHORT-011")
    @DisplayName("SHORT-011: rejects non-http(s) schemes and relative URLs")
    @ValueSource(
            strings = {
                "javascript:alert(1)",
                "file:///etc/passwd",
                "ftp://www.example.org/file",
                "data:text/html;base64,PHNjcmlwdD4=",
                "gopher://www.example.org",
                "/relative/path",
                "www.example.org/no-scheme",
                "https:///no-host",
                "https://exa mple.org/",
                "http://exa_mple.org/"
            })
    void rejectsBadSchemesAndShapes(String url) {
        assertRejected(url);
    }

    @Test
    @Tag("SHORT-011")
    @DisplayName("SHORT-011: rejects URLs with embedded credentials")
    void rejectsUserInfo() {
        assertRejected("https://user:pass@www.example.org/");
        assertRejected("https://user@www.example.org/");
    }

    @ParameterizedTest
    @Tag("SHORT-012")
    @DisplayName("SHORT-012: rejects localhost and loopback in every notation")
    @ValueSource(
            strings = {
                "http://localhost/",
                "http://LOCALHOST:8080/admin",
                "http://localhost./",
                "http://api.localhost/",
                "http://127.0.0.1/",
                "http://127.10.20.30/",
                "http://[::1]/",
                "http://0.0.0.0/",
                "http://0/",
                "http://2130706433/", // decimal 127.0.0.1
                "http://0x7f000001/", // hex
                "http://0177.0.0.1/", // octal
                "http://127.1/", // short form
                "http://[::ffff:127.0.0.1]/", // IPv4-mapped IPv6
                "http://[::]/",
                "http://rebind.example.org/" // name resolving to loopback
            })
    void rejectsLoopback(String url) {
        assertRejected(url);
    }

    @ParameterizedTest
    @Tag("SHORT-013")
    @DisplayName("SHORT-013: rejects private, CGNAT and link-local ranges")
    @ValueSource(
            strings = {
                "http://10.0.0.1/",
                "http://172.16.0.1/",
                "http://172.31.255.255/",
                "http://192.168.0.10/",
                "http://100.64.0.1/",
                "http://169.254.10.10/",
                "http://[fc00::1]/",
                "http://[fd12:3456::1]/",
                "http://[fe80::1]/",
                "http://[::ffff:10.0.0.1]/",
                "http://3232235530/", // decimal 192.168.0.10
                "http://0xa.0.0.1/", // hex octet
                "http://internal.example.org/",
                "http://v6private.example.org/",
                "http://dual.example.org/" // one public + one private address
            })
    void rejectsPrivateRanges(String url) {
        assertRejected(url);
    }

    @ParameterizedTest
    @Tag("SHORT-013")
    @DisplayName("SHORT-013: accepts public addresses next to private ranges")
    @ValueSource(strings = {"http://172.32.0.1/", "http://100.128.0.1/", "http://11.0.0.1/", "http://192.169.0.1/"})
    void acceptsAddressesJustOutsidePrivateRanges(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @Tag("SHORT-014")
    @DisplayName("SHORT-014: rejects cloud metadata endpoints")
    @ValueSource(
            strings = {
                "http://169.254.169.254/latest/meta-data/",
                "http://[fd00:ec2::254]/",
                "http://metadata.google.internal/computeMetadata/v1/",
                "http://METADATA.GOOGLE.INTERNAL./",
                "http://metadata.azure.com/",
                "http://imds.example.org/"
            })
    void rejectsMetadataEndpoints(String url) {
        assertRejected(url);
    }

    @Test
    @Tag("SHORT-013")
    @DisplayName("SHORT-013: rejects malformed numeric hosts instead of guessing")
    void rejectsMalformedNumericHosts() {
        assertRejected("http://999.1.1.1/");
        assertRejected("http://1.2.3.4.5/");
        assertRejected("http://08.0.0.1/"); // invalid octal digit
    }

    private void assertRejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .as("expected %s to be rejected", url)
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.field()).isEqualTo("url");
                    org.assertj.core.api.Assertions.assertThat(e.getMessage()).isNotBlank();
                });
    }

    private static InetAddress literal(String ip) {
        try {
            return InetAddress.getByName(ip); // IP literals never hit DNS
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(ip, e);
        }
    }
}
