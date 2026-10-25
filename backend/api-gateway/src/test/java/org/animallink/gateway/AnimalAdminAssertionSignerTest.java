package org.animallink.gateway;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class AnimalAdminAssertionSignerTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final String KEYS = "animal-v1=" + Base64.getEncoder().encodeToString(KEY);
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    @Test
    void signsShortLivedPathBoundAdminScopeWithDedicatedAudience() throws Exception {
        var signer = new AnimalAdminAssertionSigner("api-gateway", "api-gateway",
                "animal-service", Duration.ofSeconds(15), "animal-v1", KEYS,
                Clock.fixed(NOW, ZoneOffset.UTC));
        String compact = signer.sign(principal(), "GET",
                "/api/v1/admin/community-review/posts");
        SignedJWT jwt = SignedJWT.parse(compact);

        assertThat(jwt.verify(new MACVerifier(KEY))).isTrue();
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("animal-v1");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("api-gateway");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("animal-service");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("target")).isEqualTo("animal-service");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("actor_id")).isEqualTo(principal().userId());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("system_role"))
                .isEqualTo("GOVERNANCE_ADMIN");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("scope"))
                .containsExactly("community.review.read");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("request_method")).isEqualTo("GET");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("request_path"))
                .isEqualTo("/api/v1/admin/community-review/posts");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(NOW.plusSeconds(15));
    }

    @Test
    void missingDedicatedKeyAndUntrustedPrincipalFailClosed() {
        var unavailable = new AnimalAdminAssertionSigner("api-gateway", "api-gateway",
                "animal-service", Duration.ofSeconds(15), "animal-v1", "",
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> unavailable.sign(principal(), "GET", "/expected"))
                .isInstanceOf(AnimalAdminAssertionSigner.AssertionUnavailableException.class);

        var signer = new AnimalAdminAssertionSigner("api-gateway", "api-gateway",
                "animal-service", Duration.ofSeconds(15), "animal-v1", KEYS,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var ordinary = new IdentitySessionClient.GatewayAuthenticatedPrincipal("session",
                principal().userId(), "USER", NOW.plusSeconds(60), NOW.plusSeconds(120));
        assertThatThrownBy(() -> signer.sign(ordinary, "GET", "/expected"))
                .isInstanceOf(AnimalAdminAssertionSigner.AssertionRejectedException.class);
    }

    private IdentitySessionClient.GatewayAuthenticatedPrincipal principal() {
        return new IdentitySessionClient.GatewayAuthenticatedPrincipal("session",
                "00000000-0000-0000-0000-000000000001", "GOVERNANCE_ADMIN",
                NOW.plusSeconds(60), NOW.plusSeconds(120));
    }
}
