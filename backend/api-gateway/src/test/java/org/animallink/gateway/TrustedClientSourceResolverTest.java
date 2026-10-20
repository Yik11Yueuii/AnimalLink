package org.animallink.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedClientSourceResolverTest {
    @Test
    void directPeerIgnoresClientSuppliedForwardingHeaders() {
        var resolver = new TrustedClientSourceResolver("");
        var request = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("203.0.113.9", 1234))
                .header("Forwarded", "for=198.51.100.1")
                .header("X-Forwarded-For", "198.51.100.2").build();
        assertThat(resolver.resolve(MockServerWebExchange.from(request))).isEqualTo("ip:203.0.113.9");
    }

    @Test
    void configuredTrustedProxyUsesRightToLeftValidatedChain() {
        var resolver = new TrustedClientSourceResolver("10.0.0.10,10.0.0.11");
        var request = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("10.0.0.10", 1234))
                .header("X-Forwarded-For", "203.0.113.8, 10.0.0.11").build();
        assertThat(resolver.resolve(MockServerWebExchange.from(request))).isEqualTo("ip:203.0.113.8");
    }

    @Test
    void trustedProxyWithMissingOrMalformedChainFailsClosed() {
        var resolver = new TrustedClientSourceResolver("10.0.0.10");
        var missing = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("10.0.0.10", 1234)).build();
        assertThatThrownBy(() -> resolver.resolve(MockServerWebExchange.from(missing)))
                .isInstanceOf(TrustedClientSourceResolver.UnavailableTrustedSourceException.class);

        var malformed = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("10.0.0.10", 1234))
                .header("X-Forwarded-For", "attacker.example").build();
        assertThatThrownBy(() -> resolver.resolve(MockServerWebExchange.from(malformed)))
                .isInstanceOf(TrustedClientSourceResolver.UnavailableTrustedSourceException.class);
    }
}
