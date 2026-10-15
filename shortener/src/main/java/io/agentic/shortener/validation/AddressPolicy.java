package io.agentic.shortener.validation;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Classifies addresses a short link must never point at (SHORT-012 … SHORT-014). */
final class AddressPolicy {

    enum Violation {
        LOOPBACK("localhost and loopback addresses are not allowed"),
        PRIVATE("private, link-local and other non-public addresses are not allowed"),
        METADATA("cloud metadata endpoints are not allowed");

        private final String message;

        Violation(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private static final Set<InetAddress> METADATA_ADDRESSES =
            Set.of(address("169.254.169.254"), address("fd00:ec2::254"));

    private AddressPolicy() {}

    static Optional<Violation> check(InetAddress address) {
        if (METADATA_ADDRESSES.contains(address)) {
            return Optional.of(Violation.METADATA);
        }
        if (address instanceof Inet6Address v6) {
            Optional<InetAddress> embedded = embeddedIpv4(v6);
            if (embedded.isPresent()) {
                return check(embedded.get());
            }
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || isZeroNetwork(address)) {
            return Optional.of(Violation.LOOPBACK);
        }
        if (address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(address)
                || isUniqueLocalV6(address)
                || isBroadcast(address)) {
            return Optional.of(Violation.PRIVATE);
        }
        return Optional.empty();
    }

    /** 0.0.0.0/8: "this network"; most stacks route it to the local host. */
    private static boolean isZeroNetwork(InetAddress a) {
        return a instanceof Inet4Address && a.getAddress()[0] == 0;
    }

    /** 100.64.0.0/10 shared address space (RFC 6598). */
    private static boolean isCarrierGradeNat(InetAddress a) {
        byte[] b = a.getAddress();
        return a instanceof Inet4Address && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    /** fc00::/7 unique local addresses (RFC 4193). */
    private static boolean isUniqueLocalV6(InetAddress a) {
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static boolean isBroadcast(InetAddress a) {
        byte[] b = a.getAddress();
        return a instanceof Inet4Address && (b[0] & b[1] & b[2] & b[3] & 0xFF) == 0xFF;
    }

    /** IPv4-compatible ::a.b.c.d and NAT64 64:ff9b::/96 carry an IPv4 address that decides the verdict. */
    private static Optional<InetAddress> embeddedIpv4(Inet6Address v6) {
        byte[] b = v6.getAddress();
        byte[] prefix = Arrays.copyOf(b, 12);
        boolean compatible = Arrays.equals(prefix, new byte[12]) && !(b[12] == 0 && b[13] == 0 && b[14] == 0);
        boolean nat64 = Arrays.equals(prefix, new byte[] {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0});
        if (!compatible && !nat64) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
