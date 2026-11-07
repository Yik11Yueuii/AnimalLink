package org.animallink.animal.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.animallink.animal.application.ObservationFinalizationCommand;
import org.animallink.animal.domain.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationServiceAssertionVerifierTest {
    private static final byte[] KEY = new byte[32];
    private static final String PATH = "/internal/v1/observation-finalizations";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private final ObservationServiceAssertionVerifier verifier =
            new ObservationServiceAssertionVerifier(Base64.getEncoder().encodeToString(KEY));
    private final ObservationFinalizationCommand command = new ObservationFinalizationCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 1,
            "SELECT_EXISTING", UUID.randomUUID().toString(), "观察记录", List.of(), null);

    @Test void validAssertionIsOneUse() throws Exception {
        String assertion = token("animal-service", command.userId(), command.sourceAiTaskId(),
                command.campusId(), Instant.now().plusSeconds(20), KEY);
        assertThatCode(() -> verify(assertion, command)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verify(assertion, command)).isInstanceOf(ForbiddenException.class);
    }

    @Test void changedMediaOrConfirmedDraftBytesCannotReuseAssertion() throws Exception {
        String assertion = token("animal-service", command.userId(), command.sourceAiTaskId(),
                command.campusId(), Instant.now().plusSeconds(20), KEY);
        byte[] changedBody = "{\"mediaObjectKeys\":[\"replacement\"]}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify("Bearer " + assertion, "POST", PATH,
                command, changedBody)).isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> verify(assertion, command)).doesNotThrowAnyException();
    }

    @Test void unsignedWrongAudienceExpiredAndTamperedContextAreRejected() throws Exception {
        assertThatThrownBy(() -> verify(null, command)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> verify(token("other-service", command.userId(), command.sourceAiTaskId(),
                command.campusId(), Instant.now().plusSeconds(20), KEY), command))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> verify(token("animal-service", command.userId(), command.sourceAiTaskId(),
                command.campusId(), Instant.now().minusSeconds(1), KEY), command))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> verify(token("animal-service", command.userId(), command.sourceAiTaskId(),
                command.campusId(), Instant.now().plusSeconds(20), differentKey()), command))
                .isInstanceOf(ForbiddenException.class);
        String assertion = token("animal-service", command.userId(), command.sourceAiTaskId(),
                command.campusId(), Instant.now().plusSeconds(20), KEY);
        var altered = new ObservationFinalizationCommand(command.userId(), command.campusId(),
                UUID.randomUUID().toString(), command.sourceMatchingRecordId(),
                command.attemptId(), command.fencingVersion(), command.decisionType(),
                command.selectedAnimalId(), command.postText(), command.mediaObjectKeys(), command.confirmedDraft());
        assertThatThrownBy(() -> verify(assertion, altered)).isInstanceOf(ForbiddenException.class);
    }

    @Test void missingKeyFailsClosed() {
        var missing = new ObservationServiceAssertionVerifier("");
        assertThatThrownBy(() -> missing.verify("Bearer anything", "POST", PATH, command, BODY))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test void registerAndRetireRequireDistinctSignedScopesAndContexts() throws Exception {
        String registerPath = PATH + "/attempts/register";
        String retirePath = PATH + "/attempts/retire";
        var registration = new ObservationServiceAssertionVerifier.Context(command.userId(),
                command.sourceAiTaskId(), command.campusId(), command.sourceMatchingRecordId(),
                command.attemptId(), 0, command.decisionType(), command.selectedAnimalId(), null, null);
        String registerToken = operationToken(registerPath, "observation:register", registration);
        assertThatCode(() -> verifier.verifyOperation("Bearer " + registerToken,
                "POST", registerPath, registration, BODY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verifyOperation("Bearer " + registerToken,
                "POST", retirePath, registration, BODY)).isInstanceOf(ForbiddenException.class);
        var retirement = new ObservationServiceAssertionVerifier.Context(command.userId(),
                command.sourceAiTaskId(), command.campusId(), command.sourceMatchingRecordId(),
                command.attemptId(), 1, command.decisionType(), command.selectedAnimalId(),
                null, "SELECTED_ANIMAL_ARCHIVED");
        String retireToken = operationToken(retirePath, "observation:retire", retirement);
        assertThatCode(() -> verifier.verifyOperation("Bearer " + retireToken,
                "POST", retirePath, retirement, BODY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verifyOperation("Bearer " + retireToken,
                "POST", retirePath, retirement, BODY)).isInstanceOf(ForbiddenException.class);
    }

    private String operationToken(String path, String scope,
                                  ObservationServiceAssertionVerifier.Context context) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("intelligence-service")
                .subject("intelligence-service").audience("animal-service")
                .claim("scope", List.of(scope)).claim("method", "POST").claim("path", path)
                .claim("actor", context.actor()).claim("task", context.task())
                .claim("campus", context.campus()).claim("record", context.record())
                .claim("attempt", context.attempt()).claim("version", context.version())
                .claim("decision", context.decision()).claim("animal", context.animal())
                .claim("body_sha256", digest(BODY))
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(20)))
                .jwtID(UUID.randomUUID().toString()).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("observation-v1").build(), claims);
        jwt.sign(new MACSigner(KEY));
        return jwt.serialize();
    }

    private void verify(String assertion, ObservationFinalizationCommand value) {
        verifier.verify(assertion == null ? null : "Bearer " + assertion, "POST", PATH, value, BODY);
    }

    private static byte[] differentKey() {
        byte[] value = new byte[32];
        value[0] = 1;
        return value;
    }

    private String token(String audience, String actor, String task, String campus,
                         Instant expiration, byte[] key) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("intelligence-service")
                .subject("intelligence-service").audience(audience)
                .claim("scope", List.of("observation:finalize"))
                .claim("method", "POST").claim("path", PATH)
                .claim("actor", actor).claim("task", task).claim("campus", campus)
                .claim("record", command.sourceMatchingRecordId())
                .claim("attempt", command.attemptId()).claim("version", command.fencingVersion())
                .claim("decision", command.decisionType()).claim("animal", command.selectedAnimalId())
                .claim("body_sha256", digest(BODY))
                .issueTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(expiration))
                .jwtID(UUID.randomUUID().toString()).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("observation-v1").build(), claims);
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }

    private static String digest(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
