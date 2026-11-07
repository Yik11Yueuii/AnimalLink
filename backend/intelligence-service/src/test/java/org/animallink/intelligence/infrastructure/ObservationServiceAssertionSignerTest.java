package org.animallink.intelligence.infrastructure;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class ObservationServiceAssertionSignerTest {
    private final ObservationServiceAssertionSigner signer = new ObservationServiceAssertionSigner(
            Base64.getEncoder().encodeToString(new byte[32]), "observation-v1");
    private final ObservationServiceAssertionSigner.Context context =
            new ObservationServiceAssertionSigner.Context("actor", "task", "campus",
                    "record", "attempt", 2, "SELECT_EXISTING", "animal");

    @Test void eachOperationBindsExactBodyBytesAndHasFreshNonce() throws Exception {
        byte[] body = "{\"mediaObjectKeys\":[\"one.jpg\"]}".getBytes(StandardCharsets.UTF_8);
        SignedJWT register = SignedJWT.parse(signer.sign(
                "/internal/v1/observation-finalizations/attempts/register",
                "observation:register", context, body));
        SignedJWT finalize = SignedJWT.parse(signer.sign(
                "/internal/v1/observation-finalizations", "observation:finalize", context, body));
        assertThat(register.getHeader().getKeyID()).isEqualTo("observation-v1");
        assertThat(register.getJWTClaimsSet().getStringClaim("body_sha256")).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(register.getJWTClaimsSet().getStringClaim("path"))
                .isEqualTo("/internal/v1/observation-finalizations/attempts/register");
        assertThat(register.getJWTClaimsSet().getStringClaim("actor")).isEqualTo("actor");
        assertThat(register.getJWTClaimsSet().getLongClaim("version")).isEqualTo(2);
        assertThat(register.getJWTClaimsSet().getJWTID())
                .isNotEqualTo(finalize.getJWTClaimsSet().getJWTID());
    }

    @Test void missingKeyFailsClosed() {
        assertThatThrownBy(() -> new ObservationServiceAssertionSigner("", "observation-v1")
                .sign("/internal/v1/observation-finalizations", "observation:finalize",
                        context, new byte[0]))
                .isInstanceOf(IllegalStateException.class);
    }
}
