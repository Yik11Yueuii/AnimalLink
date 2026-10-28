package org.animallink.gateway;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceReadAssertionSignerTest {
    private static final byte[] INCIDENT_KEY = "incident-0123456789abcdef01234567".getBytes();
    private static final byte[] ADOPTION_KEY = "adoption-0123456789abcdef01234567".getBytes();
    @Test
    void usesTargetIsolatedAudienceKeyScopeAndPath() throws Exception {
        var signer = signer(keys("incident-v1", INCIDENT_KEY), keys("adoption-v1", ADOPTION_KEY));
        var incident = new AdminGovernanceReadPolicy.Binding("incident-service", "incident.rescue.read", "/api/v1/admin/rescues");
        SignedJWT jwt = SignedJWT.parse(signer.sign(principal(), incident));
        assertThat(jwt.verify(new MACVerifier(INCIDENT_KEY))).isTrue();
        assertThat(jwt.verify(new MACVerifier(ADOPTION_KEY))).isFalse();
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("incident-service");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("scope")).containsExactly("incident.rescue.read");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("request_path")).isEqualTo("/api/v1/admin/rescues");
    }
    @Test
    void missingTargetKeyAndOrdinaryRoleFailClosed() {
        var signer = signer("", keys("adoption-v1", ADOPTION_KEY));
        var binding = new AdminGovernanceReadPolicy.Binding("incident-service", "incident.rescue.read", "/api/v1/admin/rescues");
        assertThatThrownBy(() -> signer.sign(principal(), binding)).isInstanceOf(GovernanceReadAssertionSigner.AssertionUnavailableException.class);
        var user = new IdentitySessionClient.GatewayAuthenticatedPrincipal("session", principal().userId(), "USER", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
        assertThatThrownBy(() -> signer("", keys("adoption-v1", ADOPTION_KEY)).sign(user,
                new AdminGovernanceReadPolicy.Binding("adoption-service", "adoption.application.read", "/api/v1/admin/applications")))
                .isInstanceOf(GovernanceReadAssertionSigner.AssertionRejectedException.class);
    }
    private GovernanceReadAssertionSigner signer(String incident, String adoption) {
        return new GovernanceReadAssertionSigner("api-gateway", "api-gateway", "incident-service", Duration.ofSeconds(15), "incident-v1", incident,
                "api-gateway", "api-gateway", "adoption-service", Duration.ofSeconds(15), "adoption-v1", adoption);
    }
    private IdentitySessionClient.GatewayAuthenticatedPrincipal principal() { return new IdentitySessionClient.GatewayAuthenticatedPrincipal("session",
            "00000000-0000-0000-0000-000000000001", "GOVERNANCE_ADMIN", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120)); }
    private String keys(String id, byte[] key) { return id + "=" + Base64.getEncoder().encodeToString(key); }
}
