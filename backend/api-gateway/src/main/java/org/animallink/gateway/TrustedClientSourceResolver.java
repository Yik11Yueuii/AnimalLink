package org.animallink.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class TrustedClientSourceResolver {
    private static final int MAX_CHAIN_LENGTH = 10;
    private final Set<String> trustedProxyAddresses;

    public TrustedClientSourceResolver(
            @Value("${animallink.authentication.trusted-source.trusted-proxies:}") String configuredProxies) {
        Set<String> parsed = new LinkedHashSet<>();
        if (configuredProxies != null && !configuredProxies.isBlank()) {
            for (String candidate : configuredProxies.split(",")) parsed.add(normalize(candidate));
        }
        trustedProxyAddresses = Collections.unmodifiableSet(parsed);
    }

    public String resolve(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) throw new UnavailableTrustedSourceException();
        String peer = normalize(remote.getAddress().getHostAddress());
        if (!trustedProxyAddresses.contains(peer)) return "ip:" + peer;

        List<String> forwarded = exchange.getRequest().getHeaders().get("X-Forwarded-For");
        if (forwarded == null || forwarded.size() != 1 || forwarded.getFirst().length() > 512) {
            throw new UnavailableTrustedSourceException();
        }
        List<String> chain = Arrays.stream(forwarded.getFirst().split(",", -1)).map(String::trim).toList();
        if (chain.isEmpty() || chain.size() > MAX_CHAIN_LENGTH) throw new UnavailableTrustedSourceException();
        for (int index = chain.size() - 1; index >= 0; index--) {
            String address = normalize(chain.get(index));
            if (!trustedProxyAddresses.contains(address)) return "ip:" + address;
        }
        throw new UnavailableTrustedSourceException();
    }

    private static String normalize(String candidate) {
        try {
            if (candidate == null || candidate.isBlank() || candidate.length() > 64
                    || candidate.contains("/") || candidate.contains("%")
                    || !(candidate.matches("[0-9.]+") || candidate.matches("[0-9A-Fa-f:]+"))) {
                throw new IllegalArgumentException();
            }
            return InetAddress.getByName(candidate.trim()).getHostAddress();
        } catch (Exception invalid) {
            throw new UnavailableTrustedSourceException();
        }
    }

    public static final class UnavailableTrustedSourceException extends RuntimeException {
    }
}
