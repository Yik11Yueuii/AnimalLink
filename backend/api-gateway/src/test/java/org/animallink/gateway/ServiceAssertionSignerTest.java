package org.animallink.gateway;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void bindsOnlyCanonicalUserIdsIntoTheShortLivedAssertion() throws Exception {
        var signer = new ServiceAssertionSigner("api-gateway", "api-gateway", "identity-service",
                Duration.ofSeconds(15), "v1", "v1=" + Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes()),
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));

        String assertion = signer.signForActor("00000000-0000-0000-0000-000000000001");
        assertThat(SignedJWT.parse(assertion).getJWTClaimsSet().getStringClaim("actor"))
                .isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThatThrownBy(() -> signer.signForActor("client-supplied-user"))
                .isInstanceOf(ServiceAssertionSigner.ServiceAssertionUnavailableException.class);
    }
}
