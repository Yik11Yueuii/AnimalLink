package org.animallink.animal.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.animallink.animal.application.InternalServiceAuthorization;
import org.animallink.animal.application.ObservationFinalizationService;
import org.animallink.animal.application.ObservationFinalizationCommand;
import org.animallink.animal.application.ObservationFinalizationResult;
import org.animallink.animal.infrastructure.ObservationServiceAssertionVerifier;
import org.animallink.animal.infrastructure.ObservationBodyCaptureFilter;
import org.animallink.animal.domain.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalObservationFinalizationSecurityTest {
    @Test
    void httpRegisterRejectsTamperingAndReplayBeforeAnyBusinessWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        var service = new ObservationFinalizationService(null, null, null, null, null, null) {
            @Override public long registerAttempt(String userId, String campusId, String taskId,
                                                  String recordId, String attemptId,
                                                  String decisionType, String animalId) {
                writes.incrementAndGet();
                return 1;
            }
        };
        Set<String> nonces = ConcurrentHashMap.newKeySet();
        var verifier = new ObservationServiceAssertionVerifier(
                Base64.getEncoder().encodeToString(new byte[32]), "observation-v1",
                (nonce, expires) -> nonces.add(nonce));
        var controller = new InternalObservationFinalizationController(service,
                new InternalServiceAuthorization(), verifier);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ObservationBodyCaptureFilter())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/internal/v1/observation-finalizations/attempts/register";
        var request = new ObservationFinalizationDtos.RegisterAttemptRequest(
                "00000000-0000-0000-0000-000000000301",
                "10000000-0000-0000-0000-000000000301",
                "30000000-0000-0000-0000-000000000301",
                "40000000-0000-0000-0000-000000000301",
                "50000000-0000-0000-0000-000000000301", "SELECT_EXISTING",
                "20000000-0000-0000-0000-000000000301");
        String body = new ObjectMapper().writeValueAsString(request);
        String assertion = signRegister(path, request, body.getBytes(StandardCharsets.UTF_8));
        mvc.perform(post(path).header("X-Internal-Service", "intelligence-service")
                        .header("Authorization", "Bearer " + assertion)
                        .contentType(MediaType.APPLICATION_JSON).content(body + " "))
                .andExpect(status().isForbidden());
        assertThat(writes.get()).isZero();
        mvc.perform(post(path).header("X-Internal-Service", "intelligence-service")
                        .header("Authorization", "Bearer " + assertion)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post(path).header("X-Internal-Service", "intelligence-service")
                        .header("Authorization", "Bearer " + assertion)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(writes.get()).isEqualTo(1);
    }

    private String signRegister(String path, ObservationFinalizationDtos.RegisterAttemptRequest request,
                                byte[] body) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("intelligence-service")
                .subject("intelligence-service").audience("animal-service")
                .claim("scope", List.of("observation:register"))
                .claim("method", "POST").claim("path", path)
                .claim("actor", request.userId()).claim("campus", request.campusId())
                .claim("task", request.sourceAiTaskId()).claim("record", request.sourceMatchingRecordId())
                .claim("attempt", request.attemptId()).claim("version", 0)
                .claim("decision", request.decisionType()).claim("animal", request.selectedAnimalId())
                .claim("body_sha256", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(body)))
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(20)))
                .jwtID(UUID.randomUUID().toString()).build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID("observation-v1").build(), claims);
        token.sign(new MACSigner(new byte[32]));
        return token.serialize();
    }

    @Test
    void callerHeaderWithoutAssertionCannotReachBusinessWrite() {
        AtomicInteger writes = new AtomicInteger();
        ObservationFinalizationService service = new ObservationFinalizationService(
                null, null, null, null, null, null) {
            @Override public ObservationFinalizationResult finalizeObservation(
                    ObservationFinalizationCommand command) {
                writes.incrementAndGet();
                return null;
            }
            @Override public long registerAttempt(String userId, String campusId, String taskId,
                                                  String recordId, String attemptId,
                                                  String decisionType, String animalId) {
                writes.incrementAndGet();
                return 1;
            }
            @Override public void retireAttempt(String userId, String taskId, String recordId,
                                                String attemptId, long version, String campusId,
                                                String decisionType, String animalId,
                                                String failureCode) {
                writes.incrementAndGet();
            }
        };
        var controller = new InternalObservationFinalizationController(service,
                new InternalServiceAuthorization(), new ObservationServiceAssertionVerifier(
                        Base64.getEncoder().encodeToString(new byte[32]),
                        "observation-v1", (nonce, expires) -> true));
        var request = new MockHttpServletRequest("POST", "/internal/v1/observation-finalizations");
        var draft = new ObservationFinalizationDtos.ConfirmedDraftRequest(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
        var body = new ObservationFinalizationDtos.InternalFinalizeRequest(
                "00000000-0000-0000-0000-000000000301",
                "10000000-0000-0000-0000-000000000301",
                "30000000-0000-0000-0000-000000000301",
                "40000000-0000-0000-0000-000000000301",
                "50000000-0000-0000-0000-000000000301", 1L,
                "NO_MATCH", null, "观察记录", List.of("test/observation.jpg"), draft);
        assertThatThrownBy(() -> controller.finalizeObservation(
                "intelligence-service", null, request, body))
                .isInstanceOf(ForbiddenException.class);
        assertThat(writes.get()).isZero();
        request.setRequestURI("/internal/v1/observation-finalizations/attempts/register");
        var registration = new ObservationFinalizationDtos.RegisterAttemptRequest(
                body.userId(), body.campusId(), body.sourceAiTaskId(),
                body.sourceMatchingRecordId(), body.attemptId(), "SELECT_EXISTING",
                "20000000-0000-0000-0000-000000000301");
        assertThatThrownBy(() -> controller.registerAttempt(
                "intelligence-service", null, request, registration))
                .isInstanceOf(ForbiddenException.class);
        request.setRequestURI("/internal/v1/observation-finalizations/attempts/retire");
        var retirement = new ObservationFinalizationDtos.RetireAttemptRequest(
                body.userId(), body.campusId(), body.sourceAiTaskId(),
                body.sourceMatchingRecordId(), body.attemptId(), 1, "SELECT_EXISTING",
                "20000000-0000-0000-0000-000000000301", "SELECTED_ANIMAL_ARCHIVED");
        assertThatThrownBy(() -> controller.retireAttempt(
                "intelligence-service", null, request, retirement))
                .isInstanceOf(ForbiddenException.class);
        assertThat(writes.get()).isZero();
    }
}
