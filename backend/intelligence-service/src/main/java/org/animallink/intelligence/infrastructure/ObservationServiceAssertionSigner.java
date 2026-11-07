package org.animallink.intelligence.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** One-use, request-bound service assertion; no development bypass when the key is absent. */
@Component
public class ObservationServiceAssertionSigner {
    private final byte[] key;
    private final String keyId;

    public ObservationServiceAssertionSigner(
            @Value("${animallink.authentication.observation-assertion.key:}") String configuredKey,
            @Value("${animallink.authentication.observation-assertion.key-id:observation-v1}") String keyId) {
        byte[] parsed;
        try { parsed = Base64.getDecoder().decode(configuredKey); }
        catch (IllegalArgumentException invalid) { parsed = new byte[0]; }
        key = parsed.length >= 32 ? parsed : new byte[0];
        this.keyId = keyId;
    }

    public record Context(String actor, String task, String campus, String record,
                          String attempt, long version, String decision, String animal) { }

    public String sign(String path, String scope, Context context, byte[] body) {
        if (key.length == 0 || keyId == null || keyId.isBlank())
            throw new IllegalStateException("正式化服务断言未配置");
        try {
            Instant now = Instant.now();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("intelligence-service").subject("intelligence-service")
                    .audience("animal-service").claim("scope", List.of(scope))
                    .claim("method", "POST").claim("path", path)
                    .claim("actor", context.actor()).claim("task", context.task())
                    .claim("campus", context.campus()).claim("record", context.record())
                    .claim("attempt", context.attempt()).claim("version", context.version())
                    .claim("decision", context.decision())
                    .claim("animal", context.animal() == null ? "" : context.animal())
                    .claim("body_sha256", digest)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(20)))
                    .jwtID(UUID.randomUUID().toString()).build();
            SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .type(JOSEObjectType.JWT).keyID(keyId).build(), claims);
            token.sign(new MACSigner(key));
            return token.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("无法签发正式化服务断言");
        }
    }
}
