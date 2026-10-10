package org.animallink.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.domain.AiResult;
import org.animallink.intelligence.domain.AiTask;
import org.animallink.intelligence.domain.AiTaskRepository;
import org.animallink.intelligence.domain.AiTaskStatus;
import org.animallink.intelligence.domain.AiTaskType;
import org.animallink.intelligence.domain.CredentialPrecheckTaskBundle;
import org.animallink.intelligence.domain.ApiExceptions.Conflict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CredentialPrecheckService {
    private static final String RESULT_TYPE = "CAMPUS_CREDENTIAL_PRECHECK";
    private static final String PROMPT_VERSION = "credential-precheck-v1";
    private static final String SCHEMA_VERSION = "1.0";
    private static final BigDecimal PASS_CONFIDENCE = new BigDecimal("0.85");

    private final AiTaskRepository tasks;
    private final CredentialMaterialGateway credentialMaterial;
    private final CredentialPrecheckProvider provider;
    private final ObjectMapper objectMapper;

    public CredentialPrecheckService(AiTaskRepository tasks,
                                     CredentialMaterialGateway credentialMaterial,
                                     CredentialPrecheckProvider provider,
                                     ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.credentialMaterial = credentialMaterial;
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    public CredentialPrecheckExecution precheck(String idempotencyKey,
                                                 CredentialPrecheckCommand command) {
        validate(command);
        CredentialPrecheckTaskBundle existing = tasks
                .findCredentialPrecheckByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) return continueExisting(existing, command);

        Instant now = Instant.now();
        AiTask pending = new AiTask(UUID.randomUUID().toString(), command.applicantUserId(),
                command.campusId(), AiTaskType.CAMPUS_CREDENTIAL_PRECHECK, AiTaskStatus.PENDING,
                provider.providerName(), provider.modelName(), PROMPT_VERSION, inputSummary(command),
                now, null, null, null, null, null, null, null, 0);
        try {
            tasks.createCredentialPrecheck(pending, idempotencyKey, command.attemptId());
        } catch (DataIntegrityViolationException conflict) {
            CredentialPrecheckTaskBundle raced = tasks
                    .findCredentialPrecheckByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> conflict);
            return continueExisting(raced, command);
        }
        return new CredentialPrecheckExecution(execute(pending, command), true);
    }

    private CredentialPrecheckExecution continueExisting(CredentialPrecheckTaskBundle existing,
                                                          CredentialPrecheckCommand command) {
        if (!command.attemptId().equals(existing.externalReferenceId())
                || !requestFingerprint(command).equals(readFingerprint(existing.bundle().task().inputSummary()))) {
            throw new Conflict("Idempotency-Key 已用于不同的凭证预检请求");
        }
        AiTask task = existing.bundle().task();
        if (task.status() == AiTaskStatus.SUCCEEDED && existing.bundle().result() != null) {
            return new CredentialPrecheckExecution(readPersisted(task, existing.bundle().result()), false);
        }
        if (task.status() == AiTaskStatus.PENDING) {
            return new CredentialPrecheckExecution(execute(task, command), false);
        }
        throw new Conflict("该凭证预检正在处理或无法安全重试");
    }

    private CredentialPrecheckResponse execute(AiTask pending, CredentialPrecheckCommand command) {
        if (!tasks.markRunning(pending.id(), pending.version())) {
            throw new Conflict("AI 任务状态已变化");
        }
        AiTask running = withRunning(pending);
        StoredResult result;
        try {
            CredentialMaterialGateway.CredentialMaterial material = credentialMaterial.load(command.verificationId());
            CredentialPrecheckProvider.ProviderResult providerResult = provider.precheck(
                    new CredentialPrecheckProvider.ProviderRequest(material.content(), material.contentType(),
                            command.campusName(), command.applicantName(),
                            command.requestedMembershipType(), command.expectedGraduationDate()));
            result = normalize(providerResult, command);
        } catch (CredentialMaterialUnavailable exception) {
            result = "UNSUPPORTED_CREDENTIAL_MEDIA".equals(exception.category())
                    ? manualReviewRequired(exception.category())
                    : unavailable(exception.category());
        } catch (RuntimeException exception) {
            result = unavailable("CREDENTIAL_PRECHECK_PROVIDER_UNAVAILABLE");
        }
        Instant completedAt = Instant.now();
        AiResult persisted = new AiResult(UUID.randomUUID().toString(), pending.id(), RESULT_TYPE,
                null, writeJson(result), SCHEMA_VERSION, completedAt);
        tasks.complete(withCompleted(running, completedAt), persisted);
        return toResponse(pending.id(), pending, result);
    }

    private AiTask withRunning(AiTask pending) {
        return new AiTask(pending.id(), pending.userId(), pending.campusId(), pending.taskType(),
                AiTaskStatus.RUNNING, pending.modelProvider(), pending.modelName(), pending.promptVersion(),
                pending.inputSummary(), pending.createdAt(), Instant.now(), null, null, null, null,
                null, null, pending.version() + 1);
    }

    private AiTask withCompleted(AiTask running, Instant completedAt) {
        return new AiTask(running.id(), running.userId(), running.campusId(), running.taskType(),
                AiTaskStatus.SUCCEEDED, running.modelProvider(), running.modelName(),
                running.promptVersion(), running.inputSummary(), running.createdAt(), running.startedAt(),
                completedAt, null, null, null, null, null, running.version());
    }

    private StoredResult normalize(CredentialPrecheckProvider.ProviderResult value,
                                   CredentialPrecheckCommand command) {
        if (value == null || value.overallConfidence() == null || value.overallConfidence().signum() < 0
                || value.overallConfidence().compareTo(BigDecimal.ONE) > 0
                || blank(value.extractedCampusName()) || blank(value.extractedApplicantName())
                || blank(value.credentialType()) || value.consistencyFlags() == null) {
            return unavailable("MALFORMED_CREDENTIAL_PRECHECK_RESULT");
        }
        boolean matches = same(value.extractedCampusName(), command.campusName())
                && same(value.extractedApplicantName(), command.applicantName());
        boolean passed = value.overallConfidence().compareTo(PASS_CONFIDENCE) >= 0
                && matches && value.consistencyFlags().isEmpty();
        return new StoredResult(passed ? "PASSED" : "MANUAL_REVIEW_REQUIRED",
                value.overallConfidence(), value.extractedCampusName(), value.extractedApplicantName(),
                value.credentialType(), List.copyOf(value.consistencyFlags()),
                "AI advisory only; identity-service must retain human verification authority.", null);
    }

    private StoredResult unavailable(String category) {
        return new StoredResult("UNAVAILABLE", null, null, null, null, List.of(),
                "Credential precheck is unavailable; identity-service must use its manual review path.",
                category);
    }

    private StoredResult manualReviewRequired(String category) {
        return new StoredResult("MANUAL_REVIEW_REQUIRED", null, null, null, null,
                List.of(category),
                "Credential media requires manual review; identity-service retains verification authority.",
                category);
    }

    private CredentialPrecheckResponse readPersisted(AiTask task, AiResult result) {
        try {
            StoredResult stored = objectMapper.readValue(result.structuredJson(), StoredResult.class);
            return toResponse(task.id(), task, stored);
        } catch (JsonProcessingException exception) {
            throw new Conflict("已持久化的凭证预检结果无效");
        }
    }

    private CredentialPrecheckResponse toResponse(String taskId, AiTask task, StoredResult result) {
        return new CredentialPrecheckResponse(taskId, result.status(), task.modelProvider(), task.modelName(),
                result.overallConfidence(), result.extractedCampusName(), result.extractedApplicantName(),
                result.credentialType(), result.consistencyFlags(), result.summary(), result.errorCategory());
    }

    private String inputSummary(CredentialPrecheckCommand command) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("attemptId", command.attemptId());
        summary.put("verificationId", command.verificationId());
        summary.put("requestedMembershipType", command.requestedMembershipType());
        summary.put("expectedGraduationDatePresent", command.expectedGraduationDate() != null);
        summary.put("requestFingerprint", requestFingerprint(command));
        return writeJson(summary);
    }

    private String requestFingerprint(CredentialPrecheckCommand command) {
        String input = String.join("\u001f", command.attemptId(), command.verificationId(),
                command.applicantUserId(), command.campusId(), command.campusName(), command.applicantName(),
                command.requestedMembershipType(), String.valueOf(command.expectedGraduationDate()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成请求指纹", exception);
        }
    }

    private String readFingerprint(String inputSummary) {
        try {
            JsonNode root = objectMapper.readTree(inputSummary);
            return root.path("requestFingerprint").asText();
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化凭证预检结果", exception);
        }
    }

    private void validate(CredentialPrecheckCommand command) {
        if (blank(command.attemptId()) || blank(command.verificationId()) || blank(command.applicantUserId())
                || blank(command.campusId()) || blank(command.campusName()) || blank(command.applicantName())
                || blank(command.requestedMembershipType())) {
            throw new IllegalArgumentException("credential precheck request contains required blank values");
        }
    }

    private boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record StoredResult(String status, BigDecimal overallConfidence, String extractedCampusName,
                                String extractedApplicantName, String credentialType,
                                List<String> consistencyFlags, String summary, String errorCategory) {
    }
}
