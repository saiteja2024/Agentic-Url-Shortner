package io.agentic.shortener.validation;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises IP-address hosts the way browsers do (WHATWG URL host parsing), so a blocked address
 * can't slip through in an alternative notation: decimal {@code 2130706433}, hex {@code 0x7f000001},
 * octal {@code 0177.0.0.1}, short {@code 127.1}, and IPv6 including IPv4-mapped forms.
 */
final class IpLiterals {

    private static final Pattern NUMERIC_PART = Pattern.compile("0[xX][0-9a-fA-F]*|[0-9]+");
    private static final BigInteger MAX_IPV4 = BigInteger.valueOf(0xFFFF_FFFFL);

    private IpLiterals() {}

    /**
     * @param host normalised host (lowercase, no brackets, no trailing dot)
     * @return the address if {@code host} is an IP literal, empty if it is a domain name
     * @throws IllegalArgumentException if {@code host} looks numeric but is not a valid address
     */
    static Optional<InetAddress> parse(String host) {
        if (host.indexOf(':') >= 0) {
            return Optional.of(parseIpv6(host));
        }
        String[] parts = host.split("\\.", -1);
        // WHATWG "ends in a number": a host whose last label is numeric must be a valid IPv4.
        if (!NUMERIC_PART.matcher(parts[parts.length - 1]).matches()) {
            return Optional.empty();
        }
        return Optional.of(parseIpv4(parts));
    }

    private static InetAddress parseIpv6(String host) {
        if (host.indexOf('%') >= 0) {
            throw new IllegalArgumentException("IPv6 zone identifiers are not allowed");
        }
        try {
            // A literal containing ':' is parsed, never resolved. IPv4-mapped forms come back as Inet4Address.
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid IPv6 address", e);
        }
    }

    private static InetAddress parseIpv4(String[] parts) {
        if (parts.length > 4) {
            throw new IllegalArgumentException("too many IPv4 parts");
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < parts.length; i++) {
            BigInteger part = parsePart(parts[i]);
            boolean last = i == parts.length - 1;
            // Non-final parts are single octets; the final part fills the remaining bytes.
            int bytes = last ? 4 - i : 1;
            if (part.bitLength() > bytes * 8) {
                throw new IllegalArgumentException("IPv4 part out of range");
            }
            value = value.shiftLeft(bytes * 8).or(part);
        }
        if (value.compareTo(MAX_IPV4) > 0) {
            throw new IllegalArgumentException("IPv4 address out of range");
        }
        byte[] address = new byte[4];
        long v = value.longValue();
        for (int i = 3; i >= 0; i--) {
            address[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("4-byte address rejected", e);
        }
    }

    private static BigInteger parsePart(String part) {
        String p = part.toLowerCase(Locale.ROOT);
        if (!NUMERIC_PART.matcher(p).matches()) {
            throw new IllegalArgumentException("non-numeric IPv4 part");
        }
        try {
            if (p.startsWith("0x")) {
                return p.length() == 2 ? BigInteger.ZERO : new BigInteger(p.substring(2), 16);
            }
            if (p.length() > 1 && p.startsWith("0")) {
                return new BigInteger(p.substring(1), 8);
            }
            return new BigInteger(p);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid IPv4 part", e);
        }
    }
}
