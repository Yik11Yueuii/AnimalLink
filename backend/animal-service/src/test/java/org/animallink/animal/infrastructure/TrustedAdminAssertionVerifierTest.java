package org.animallink.animal.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TrustedAdminAssertionVerifierTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final String KEYS = "animal-v1=" + Base64.getEncoder().encodeToString(KEY);
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final String PATH = "/api/v1/admin/community-review/posts";

    @Test
    void verifiesSignatureAudienceTimePermissionAndRequestBindingOnce() throws Exception {
        var verifier = verifier(KEYS);
        String assertion = token(KEY, "animal-v1", "animal-service", "GOVERNANCE_ADMIN",
                List.of("community.review.read"), "GET", PATH, NOW, NOW.plusSeconds(15));

        var principal = verifier.verify("Bearer " + assertion, "GET", PATH);
        assertThat(principal.userId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(principal.systemRole()).isEqualTo("GOVERNANCE_ADMIN");
        assertThatThrownBy(() -> verifier.verify("Bearer " + assertion, "GET", PATH))
                .isInstanceOf(TrustedAdminAssertionVerifier.AssertionRejectedException.class);
    }

    @Test
    void rejectsWrongSignatureAudienceExpiryMethodAndPath() throws Exception {
        byte[] other = "abcdef0123456789abcdef0123456789".getBytes();
        assertRejected(token(other, "animal-v1", "animal-service", "GOVERNANCE_ADMIN",
                List.of("community.review.read"), "GET", PATH, NOW, NOW.plusSeconds(15)),
                "GET", PATH);
        assertRejected(token(KEY, "animal-v1", "identity-service", "GOVERNANCE_ADMIN",
                List.of("community.review.read"), "GET", PATH, NOW, NOW.plusSeconds(15)),
                "GET", PATH);
        assertRejected(token(KEY, "animal-v1", "animal-service", "GOVERNANCE_ADMIN",
                List.of("community.review.read"), "GET", PATH, NOW.minusSeconds(40),
                NOW.minusSeconds(20)), "GET", PATH);
        assertRejected(token(KEY, "animal-v1", "animal-service", "GOVERNANCE_ADMIN",
                List.of("community.review.read"), "GET", PATH, NOW, NOW.plusSeconds(15)),
                "POST", PATH);
        assertRejected(token(KEY, "animal-v1", "animal-service", "GOVERNANCE_ADMIN",
                List.of("community.review.read"), "GET", PATH, NOW, NOW.plusSeconds(15)),
                "GET", PATH + "/extra");
    }

    @Test
    void distinguishesAuthenticatedButInsufficientPermissionAndMissingConfiguration() throws Exception {
        String ordinary = token(KEY, "animal-v1", "animal-service", "USER",
                List.of("community.review.read"), "GET", PATH, NOW, NOW.plusSeconds(15));
        assertThatThrownBy(() -> verifier(KEYS).verify("Bearer " + ordinary, "GET", PATH))
                .isInstanceOf(TrustedAdminAssertionVerifier.AssertionForbiddenException.class);
        String wrongScope = token(KEY, "animal-v1", "animal-service", "GOVERNANCE_ADMIN",
                List.of("community.write"), "GET", PATH, NOW, NOW.plusSeconds(15));
        assertThatThrownBy(() -> verifier(KEYS).verify("Bearer " + wrongScope, "GET", PATH))
                .isInstanceOf(TrustedAdminAssertionVerifier.AssertionForbiddenException.class);
        assertThatThrownBy(() -> verifier("").verify("Bearer " + ordinary, "GET", PATH))
                .isInstanceOf(TrustedAdminAssertionVerifier.AssertionUnavailableException.class);
    }

    private void assertRejected(String token, String method, String path) {
        assertThatThrownBy(() -> verifier(KEYS).verify("Bearer " + token, method, path))
                .isInstanceOf(TrustedAdminAssertionVerifier.AssertionRejectedException.class);
    }

    private TrustedAdminAssertionVerifier verifier(String keys) {
        return new TrustedAdminAssertionVerifier("api-gateway", "api-gateway",
                "animal-service", Duration.ofSeconds(30), Duration.ofSeconds(5), 100,
                keys, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token(byte[] key, String keyId, String audience, String role,
                         List<String> scopes, String method, String path,
                         Instant issuedAt, Instant expiresAt) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway")
                .audience(audience).claim("target", audience)
                .claim("actor_id", "00000000-0000-0000-0000-000000000001")
                .claim("system_role", role).claim("scope", scopes)
                .claim("request_method", method).claim("request_path", path)
                .issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID(keyId).build(), claims);
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }
}
