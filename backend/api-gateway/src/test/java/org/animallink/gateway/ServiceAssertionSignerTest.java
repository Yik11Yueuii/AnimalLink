package org.animallink.gateway;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceAssertionSignerTest {
    @Test
    void missingKeyConfigurationFailsClosed() {
        var signer = new ServiceAssertionSigner("api-gateway", "api-gateway", "identity-service",
                Duration.ofSeconds(15), "", "",
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(signer::sign)
                .isInstanceOf(ServiceAssertionSigner.ServiceAssertionUnavailableException.class);
    }
}
