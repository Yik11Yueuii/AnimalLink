package org.animallink.identity.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceAssertionVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT_KEY = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String KEYS = "v1=" + Base64.getEncoder().encodeToString(KEY);

    @Test
    void missingKeyConfigurationFailsClosedAsUnavailable() {
        var verifier = verifier("");
        assertThatThrownBy(() -> verifier.verify("Bearer any"))
                .isInstanceOf(ServiceAssertionVerifier.ServiceAssertionUnavailableException.class);
    }

    @Test
    void verifiesRequiredClaimsAndRejectsReplayWrongTargetAndExcessiveLifetime() throws Exception {
        var verifier = verifier(KEYS);
        String valid = token(KEY, "v1", "api-gateway", "api-gateway",
                "identity-service", NOW, NOW.plusSeconds(15), "abcdefghijklmnop");
        assertThat(verifier.verify("Bearer " + valid).subject()).isEqualTo("api-gateway");
        assertThatThrownBy(() -> verifier.verify("Bearer " + valid))
                .isInstanceOf(ServiceAssertionVerifier.ServiceAssertionRejectedException.class);
        assertThatThrownBy(() -> verifier.verify("Bearer "
                + token(KEY, "v1", "api-gateway", "api-gateway",
                "animal-service", NOW, NOW.plusSeconds(15), "abcdefghijklmnop2")))
                .isInstanceOf(ServiceAssertionVerifier.ServiceAssertionRejectedException.class);
        assertThatThrownBy(() -> verifier.verify("Bearer "
                + token(KEY, "v1", "api-gateway", "api-gateway",
                "identity-service", NOW, NOW.plusSeconds(31), "abcdefghijklmnop3")))
                .isInstanceOf(ServiceAssertionVerifier.ServiceAssertionRejectedException.class);
    }

    @Test
    void rejectsWrongIssuerAndCallerWhileAcceptingConfiguredRotationKey() throws Exception {
        String rotatingKeys = KEYS + ";v2=" + Base64.getEncoder().encodeToString(NEXT_KEY);
        var verifier = verifier(rotatingKeys);

        assertThat(verifier.verify("Bearer " + token(NEXT_KEY, "v2", "api-gateway", "api-gateway",
                "identity-service", NOW, NOW.plusSeconds(15), "rotation-token-id")).subject())
                .isEqualTo("api-gateway");
        assertThatThrownBy(() -> verifier.verify("Bearer " + token(KEY, "v1", "other-issuer", "api-gateway",
                "identity-service", NOW, NOW.plusSeconds(15), "wrong-issuer-token")))
                .isInstanceOf(ServiceAssertionVerifier.ServiceAssertionRejectedException.class);
        assertThatThrownBy(() -> verifier.verify("Bearer " + token(KEY, "v1", "api-gateway", "other-service",
                "identity-service", NOW, NOW.plusSeconds(15), "wrong-caller-token")))
                .isInstanceOf(ServiceAssertionVerifier.ServiceAssertionRejectedException.class);
    }

    @Test
    void permitsAnOptionalCanonicalActorButRejectsAnInvalidActorClaim() throws Exception {
        var verifier = verifier(KEYS);
        String actor = "00000000-0000-0000-0000-000000000001";
        assertThat(verifier.verify("Bearer " + tokenWithActor(KEY, "v1", "identity-service", actor,
                "actor-assertion-01")).actorUserId()).isEqualTo(actor);
        assertThatThrownBy(() -> verifier.verify("Bearer " + tokenWithActor(KEY, "v1", "identity-service",
                "not-a-user-id", "actor-assertion-02")))
                .isInstanceOf(ServiceAssertionVerifier.ServiceAssertionRejectedException.class);
    }

    private ServiceAssertionVerifier verifier(String keys) {
        return new ServiceAssertionVerifier("api-gateway", "api-gateway", "identity-service",
                Duration.ofSeconds(30), Duration.ofSeconds(5), 10, keys,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token(byte[] signingKey, String keyId, String issuer, String subject,
                         String target, Instant issuedAt, Instant expiresAt, String jti) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                .audience(target).claim("target", target).issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt)).jwtID(jti).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID(keyId).build(), claims);
        jwt.sign(new MACSigner(signingKey));
        return jwt.serialize();
    }

    private String tokenWithActor(byte[] signingKey, String keyId, String target, String actor, String jti) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway")
                .audience(target).claim("target", target).claim("actor", actor).issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(15))).jwtID(jti).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID(keyId).build(), claims);
        jwt.sign(new MACSigner(signingKey));
        return jwt.serialize();
    }
}
