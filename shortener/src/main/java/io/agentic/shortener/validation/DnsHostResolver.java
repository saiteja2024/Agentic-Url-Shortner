package io.agentic.shortener.validation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Resolves through the JVM's resolver (all A and AAAA records). */
public final class DnsHostResolver implements HostResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
