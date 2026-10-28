package org.animallink.incident.infrastructure;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentAdminAssertionVerifierTest {
    private static final byte[] KEY = "incident-0123456789abcdef01234567".getBytes();
    private static final String KEYS = "incident-v1=" + Base64.getEncoder().encodeToString(KEY);
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final String PATH = "/api/v1/admin/rescues";
    @Test void verifiesExactBindingOnceAndRejectsReplay() throws Exception {
        var verifier = verifier(KEYS); String token = token("incident-service", "GOVERNANCE_ADMIN", "incident.rescue.read", PATH);
        assertThat(verifier.verify("Bearer " + token, "GET", PATH).scope()).isEqualTo("incident.rescue.read");
        assertThatThrownBy(() -> verifier.verify("Bearer " + token, "GET", PATH)).isInstanceOf(IncidentAdminAssertionVerifier.Rejected.class);
    }
    @Test void rejectsWrongAudienceScopePathRoleAndMissingConfiguration() throws Exception {
        assertRejected(token("adoption-service", "GOVERNANCE_ADMIN", "incident.rescue.read", PATH), PATH);
        assertThatThrownBy(() -> verifier(KEYS).verify("Bearer " + token("incident-service", "GOVERNANCE_ADMIN", "wrong", PATH), "GET", PATH)).isInstanceOf(IncidentAdminAssertionVerifier.Forbidden.class);
        assertRejected(token("incident-service", "GOVERNANCE_ADMIN", "incident.rescue.read", PATH), PATH + "/00000000-0000-0000-0000-000000000001");
        assertThatThrownBy(() -> verifier(KEYS).verify("Bearer " + token("incident-service", "USER", "incident.rescue.read", PATH), "GET", PATH)).isInstanceOf(IncidentAdminAssertionVerifier.Forbidden.class);
        assertThatThrownBy(() -> verifier("").verify("Bearer value", "GET", PATH)).isInstanceOf(IncidentAdminAssertionVerifier.Unavailable.class);
    }
    private void assertRejected(String token, String path) { assertThatThrownBy(() -> verifier(KEYS).verify("Bearer " + token, "GET", path)).isInstanceOf(IncidentAdminAssertionVerifier.Rejected.class); }
    private IncidentAdminAssertionVerifier verifier(String keys) { return new IncidentAdminAssertionVerifier("api-gateway", "api-gateway", "incident-service",
            Duration.ofSeconds(30), Duration.ofSeconds(5), 100, keys, Clock.fixed(NOW, ZoneOffset.UTC)); }
    private String token(String audience, String role, String scope, String path) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway").audience(audience).claim("target", audience)
                .claim("actor_id", "00000000-0000-0000-0000-000000000001").claim("system_role", role).claim("scope", List.of(scope))
                .claim("request_method", "GET").claim("request_path", path).issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(15)))
                .jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID("incident-v1").build(), claims); jwt.sign(new MACSigner(KEY)); return jwt.serialize();
    }
}
