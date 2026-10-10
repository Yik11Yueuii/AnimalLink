package org.animallink.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusRepository;
import org.animallink.identity.domain.CampusVerification;
import org.animallink.identity.domain.CampusVerificationRepository;
import org.animallink.identity.domain.CredentialMaterial;
import org.animallink.identity.domain.CredentialMaterialRepository;
import org.animallink.identity.domain.CredentialMaterialStatus;
import org.animallink.identity.domain.CredentialPrecheckAttempt;
import org.animallink.identity.domain.CredentialPrecheckAttemptRepository;
import org.animallink.identity.domain.CredentialPrecheckStatus;
import org.animallink.identity.domain.CredentialPrecheckWriteback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class CredentialPrecheckDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CredentialPrecheckDispatcher.class);
    private static final int MAX_PROVIDER = 64;
    private static final int MAX_MODEL = 128;
    private static final int MAX_SCHOOL = 160;
    private static final int MAX_PERSON = 80;
    private static final int MAX_TYPE = 64;
    private static final int MAX_SUMMARY = 1000;
    private static final int MAX_CATEGORY = 64;

    private final CredentialPrecheckAttemptRepository attempts;
    private final CredentialPrecheckAttemptTransactions transactions;
    private final CampusVerificationRepository verifications;
    private final CampusRepository campuses;
    private final CredentialMaterialRepository materials;
    private final CredentialPrecheckClient client;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final boolean enabled;

    public CredentialPrecheckDispatcher(CredentialPrecheckAttemptRepository attempts,
                                        CredentialPrecheckAttemptTransactions transactions,
                                        CampusVerificationRepository verifications,
                                        CampusRepository campuses,
                                        CredentialMaterialRepository materials,
                                        CredentialPrecheckClient client,
                                        ObjectMapper objectMapper,
                                        @Value("${animallink.credential-precheck.dispatcher.batch-size:10}") int batchSize,
                                        @Value("${animallink.credential-precheck.dispatcher.enabled:true}") boolean enabled) {
        this.attempts = attempts;
        this.transactions = transactions;
        this.verifications = verifications;
        this.campuses = campuses;
        this.materials = materials;
        this.client = client;
        this.objectMapper = objectMapper;
        this.batchSize = Math.min(Math.max(batchSize, 1), 100);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${animallink.credential-precheck.dispatcher.fixed-delay-ms:5000}")
    public void scheduledDispatch() {
        if (enabled) dispatchPending();
    }

    public void dispatchPending() {
        for (CredentialPrecheckAttempt attempt : attempts.findPendingForDispatch(batchSize)) {
            try {
                dispatchAttempt(attempt.id());
            } catch (RuntimeException exception) {
                log.warn("credential precheck dispatch isolated attemptId={}", attempt.id());
            }
        }
    }

    public void dispatchAttempt(String attemptId) {
        if (!transactions.claim(attemptId, Instant.now())) return;
        CredentialPrecheckAttempt attempt = attempts.findAttemptById(attemptId).orElse(null);
        if (attempt == null || attempt.status() != CredentialPrecheckStatus.PROCESSING) return;
        try {
            DispatchContext context = contextFor(attempt);
            CredentialPrecheckClient.CredentialPrecheckResult result = client.precheck(new CredentialPrecheckClient.CredentialPrecheckRequest(
                    attempt.id(), context.verification().id(), context.verification().userId(), context.verification().campusId(),
                    context.campus().name(), context.verification().applicantName(),
                    context.verification().requestedMembershipType().name(), context.verification().expectedGraduationDate()));
            complete(attempt.id(), validatedWriteback(result));
        } catch (CredentialPrecheckClientException exception) {
            unavailable(attempt.id(), exception.errorCategory());
        } catch (IllegalStateException exception) {
            unavailable(attempt.id(), "IDENTITY_PRECHECK_CONTEXT_INVALID");
        } catch (RuntimeException exception) {
            unavailable(attempt.id(), "INTELLIGENCE_UNAVAILABLE");
        }
    }

    private DispatchContext contextFor(CredentialPrecheckAttempt attempt) {
        CampusVerification verification = verifications.findVerificationById(attempt.verificationId())
                .orElseThrow(() -> new IllegalStateException("missing verification"));
        Campus campus = campuses.findById(verification.campusId())
                .orElseThrow(() -> new IllegalStateException("missing campus"));
        if (verification.materialMediaId() == null) throw new IllegalStateException("missing material");
        CredentialMaterial material = materials.findById(verification.materialMediaId())
                .orElseThrow(() -> new IllegalStateException("missing material"));
        if (material.status() != CredentialMaterialStatus.ATTACHED || !verification.userId().equals(material.ownerUserId())) {
            throw new IllegalStateException("invalid material binding");
        }
        return new DispatchContext(verification, campus);
    }

    private CredentialPrecheckWriteback validatedWriteback(CredentialPrecheckClient.CredentialPrecheckResult value) {
        if (value == null || !uuid(value.taskId()) || value.status() == null) throw contractError();
        CredentialPrecheckStatus status = switch (value.status()) {
            case "PASSED" -> CredentialPrecheckStatus.PASSED;
            case "MANUAL_REVIEW_REQUIRED" -> CredentialPrecheckStatus.MANUAL_REVIEW_REQUIRED;
            case "UNAVAILABLE" -> CredentialPrecheckStatus.UNAVAILABLE;
            default -> throw contractError();
        };
        if (value.overallConfidence() != null && (value.overallConfidence().signum() < 0
                || value.overallConfidence().compareTo(BigDecimal.ONE) > 0)) throw contractError();
        List<String> flags = value.consistencyFlags() == null ? List.of() : List.copyOf(value.consistencyFlags());
        if (flags.size() > 20 || flags.stream().anyMatch(flag -> blank(flag) || flag.length() > 64)) throw contractError();
        return new CredentialPrecheckWriteback(value.taskId(), status,
                bounded(value.provider(), MAX_PROVIDER), bounded(value.modelName(), MAX_MODEL), value.overallConfidence(),
                bounded(value.extractedSchoolName(), MAX_SCHOOL), bounded(value.extractedPersonName(), MAX_PERSON),
                bounded(value.credentialType(), MAX_TYPE), json(flags), bounded(value.summary(), MAX_SUMMARY),
                bounded(value.errorCategory(), MAX_CATEGORY));
    }

    private void complete(String attemptId, CredentialPrecheckWriteback result) {
        if (!transactions.complete(attemptId, result, Instant.now())) {
            log.info("credential precheck result ignored after state transition attemptId={}", attemptId);
        }
    }

    private void unavailable(String attemptId, String errorCategory) {
        complete(attemptId, new CredentialPrecheckWriteback(null, CredentialPrecheckStatus.UNAVAILABLE,
                null, null, null, null, null, null, "[]",
                "Credential precheck is unavailable; governance review remains available.", errorCategory));
    }

    private CredentialPrecheckClientException contractError() {
        return new CredentialPrecheckClientException("INTELLIGENCE_CONTRACT_ERROR");
    }

    private String json(List<String> flags) {
        try {
            return objectMapper.writeValueAsString(flags);
        } catch (JsonProcessingException exception) {
            throw contractError();
        }
    }

    private static boolean uuid(String value) {
        try { UUID.fromString(value); return true; } catch (RuntimeException exception) { return false; }
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > max) throw new CredentialPrecheckClientException("INTELLIGENCE_CONTRACT_ERROR");
        return value;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record DispatchContext(CampusVerification verification, Campus campus) { }
}
