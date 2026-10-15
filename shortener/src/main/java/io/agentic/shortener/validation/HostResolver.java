package io.agentic.shortener.validation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Name → addresses lookup; injectable so tests never depend on real DNS. */
@FunctionalInterface
public interface HostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
