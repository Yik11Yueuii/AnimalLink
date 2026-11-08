package org.animallink.animal.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.animallink.animal.application.ObservationFinalizationCommand;
import org.animallink.animal.domain.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Verifies the Intelligence caller and binds its assertion to the exact finalization context. */
@Component
public class ObservationServiceAssertionVerifier {
    private final byte[] key;
    private final String keyId;
    private final ObservationAssertionReplayStore replay;

    @Autowired
    public ObservationServiceAssertionVerifier(
            @Value("${animallink.authentication.observation-assertion.key:}") String configuredKey,
            @Value("${animallink.authentication.observation-assertion.key-id:observation-v1}") String keyId,
            ObservationAssertionReplayStore replay) {
        byte[] parsed;
        try { parsed = Base64.getDecoder().decode(configuredKey); }
        catch (IllegalArgumentException invalid) { parsed = new byte[0]; }
        key = parsed.length >= 32 ? parsed : new byte[0];
        this.keyId = keyId;
        this.replay = replay;
    }

    // Package-local constructor is used only by unit tests; Spring always selects @Autowired above.
    ObservationServiceAssertionVerifier(String configuredKey) {
        this(configuredKey, "observation-v1", new ObservationAssertionReplayStore() {
            private final java.util.Set<String> used = ConcurrentHashMap.newKeySet();
            public boolean consume(String nonce, Instant expiresAt) { return used.add(nonce); }
        });
    }

    public void verify(String authorization, String method, String path,
                       ObservationFinalizationCommand command, byte[] body) {
        verifyOperation(authorization, method, path, new Context(command.userId(),
                command.sourceAiTaskId(), command.campusId(), command.sourceMatchingRecordId(),
                command.attemptId(), command.fencingVersion(), command.decisionType(),
                command.selectedAnimalId(), command.postText(), null), body);
    }

    public record Context(String actor, String task, String campus, String record,
                          String attempt, long version, String decision, String animal,
                          String postText, String failureCode) { }

    public void verifyOperation(String authorization, String method, String path, Context context,
                                byte[] body) {
        if (key.length == 0 || keyId == null || keyId.isBlank() || body == null)
            throw new ForbiddenException("正式化服务断言不可用");
        if (authorization == null || authorization.length() > 4096 || !authorization.startsWith("Bearer "))
            throw new ForbiddenException("缺少正式化服务断言");
        try {
            SignedJWT token = SignedJWT.parse(authorization.substring(7));
            if (!JWSAlgorithm.HS256.equals(token.getHeader().getAlgorithm())
                    || !JOSEObjectType.JWT.equals(token.getHeader().getType())
                    || !keyId.equals(token.getHeader().getKeyID())
                    || !token.verify(new MACVerifier(key))) throw new IllegalArgumentException();
            var claims = token.getJWTClaimsSet();
            Date issued = claims.getIssueTime(), expires = claims.getExpirationTime();
            Instant now = Instant.now();
            String nonce = claims.getJWTID();
            String scope = switch (path) {
                case "/internal/v1/observation-finalizations" -> "observation:finalize";
                case "/internal/v1/observation-finalizations/attempts/register" -> "observation:register";
                case "/internal/v1/observation-finalizations/attempts/retire" -> "observation:retire";
                default -> throw new IllegalArgumentException();
            };
            if (!"intelligence-service".equals(claims.getIssuer())
                    || !"intelligence-service".equals(claims.getSubject())
                    || !List.of("animal-service").equals(claims.getAudience())
                    || !List.of(scope).equals(claims.getStringListClaim("scope"))
                    || !"POST".equals(method) || !method.equals(claims.getStringClaim("method"))
                    || !path.equals(claims.getStringClaim("path"))
                    || !Objects.equals(context.actor(), claims.getStringClaim("actor"))
                    || !Objects.equals(context.task(), claims.getStringClaim("task"))
                    || !Objects.equals(context.campus(), claims.getStringClaim("campus"))
                    || !Objects.equals(context.record(), claims.getStringClaim("record"))
                    || !Objects.equals(context.attempt(), claims.getStringClaim("attempt"))
                    || context.version() != claims.getLongClaim("version")
                    || !Objects.equals(context.decision() == null ? "" : context.decision(),
                            claims.getStringClaim("decision"))
                    || !Objects.equals(context.animal() == null ? "" : context.animal(),
                            claims.getStringClaim("animal"))
                    || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),
                            HexFormat.of().parseHex(claims.getStringClaim("body_sha256")))
                    || issued == null || expires == null || nonce == null
                    || !UUID.fromString(nonce).toString().equalsIgnoreCase(nonce)
                    || issued.toInstant().isAfter(now.plusSeconds(5))
                    || !expires.toInstant().isAfter(now)
                    || !expires.toInstant().isAfter(issued.toInstant())
                    || expires.toInstant().isAfter(issued.toInstant().plusSeconds(30)))
                throw new IllegalArgumentException();
            if (!replay.consume(nonce, expires.toInstant())) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new ForbiddenException("正式化服务断言无效");
        }
    }
}
