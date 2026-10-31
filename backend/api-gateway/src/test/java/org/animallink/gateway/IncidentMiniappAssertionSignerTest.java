package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class IncidentMiniappAssertionSignerTest {
    private static final String ACTOR = "23000000-0000-4000-8000-000000000001";
    private static final String PATH = "/auth/wechat/rescue-drafts";

    @Test
    void signsOnlyTheActualUserRoleReturnedByIdentity() throws Exception {
        var signer = signer();
        String token = signer.sign(ACTOR, "USER", "POST", PATH);
        assertEquals("USER", SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("system_role"));
        assertThrows(IncidentMiniappAssertionSigner.Unavailable.class,
                () -> signer.sign(ACTOR, "GOVERNANCE_ADMIN", "POST", PATH));
        assertThrows(IncidentMiniappAssertionSigner.Unavailable.class,
                () -> signer.sign(ACTOR, null, "POST", PATH));
    }

    private static IncidentMiniappAssertionSigner signer() {
        String key = Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        return new IncidentMiniappAssertionSigner(
                "api-gateway", "api-gateway", "incident-service", Duration.ofSeconds(15),
                "test", "test=" + key, Clock.systemUTC());
    }
}
