package org.animallink.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ValidationException;
import org.animallink.intelligence.domain.*;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ObservationParsingService {
    private static final String RESULT_TYPE = "ANIMAL_OBSERVATION_DRAFT";
    private static final String SCHEMA_VERSION = "1.0";

    private final AiTaskRepository repository;
    private final CampusMembershipAuthorization authorization;
    private final MediaObjectGateway mediaGateway;
    private final MultimodalModelClient modelClient;
    private final AnimalObservationDraftValidator validator;
    private final VersionedObservationPrompt prompt;
    private final ObjectMapper objectMapper;
    private final ObjectMapper strictMapper;

    public ObservationParsingService(AiTaskRepository repository,
                                     CampusMembershipAuthorization authorization,
                                     MediaObjectGateway mediaGateway,
                                     MultimodalModelClient modelClient,
                                     AnimalObservationDraftValidator validator,
                                     VersionedObservationPrompt prompt,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.authorization = authorization;
        this.mediaGateway = mediaGateway;
        this.modelClient = modelClient;
        this.validator = validator;
        this.prompt = prompt;
        this.objectMapper = objectMapper;
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public AiTaskView parse(ObservationParseCommand command) {
        IdentityGateway.CurrentUser user = authorization.requireActiveMember(command.campusId());
        List<MediaObjectGateway.MediaInput> media = mediaGateway.loadAll(command.mediaObjectKeys());
        Instant now = Instant.now();
        String taskId = UUID.randomUUID().toString();
        AiTask pending = new AiTask(taskId, user.id(), command.campusId(),
                AiTaskType.ANIMAL_OBSERVATION_PARSE, AiTaskStatus.PENDING,
                modelClient.providerName(), modelClient.modelName(), VersionedObservationPrompt.VERSION,
                inputSummary(command), now, null, null, null, null, null, null, null, 0);
        repository.create(pending);
        if (!repository.markRunning(taskId, 0)) throw new Conflict("AI 任务状态已变化");
        AiTask running = new AiTask(taskId, user.id(), command.campusId(), pending.taskType(),
                AiTaskStatus.RUNNING, pending.modelProvider(), pending.modelName(), pending.promptVersion(),
                pending.inputSummary(), pending.createdAt(), Instant.now(), null, null,
                null, null, null, null, 1);
        MultimodalModelClient.ModelResponse response;
        try {
            response = modelClient.analyze(
                    new MultimodalModelClient.ModelRequest(prompt.content(), command.text(),
                            command.locationDescription(), command.occurredAt(), media));
        } catch (ProviderTimeout exception) {
            fail(running, "PROVIDER_TIMEOUT", "模型服务响应超时");
            throw exception;
        } catch (ProviderUnavailable exception) {
            fail(running, "PROVIDER_UNAVAILABLE", "模型服务暂不可用");
            throw exception;
        } catch (RuntimeException exception) {
            fail(running, "PROVIDER_UNAVAILABLE", "模型服务调用失败");
            throw new ProviderUnavailable("模型服务暂不可用", exception);
        }
        AnimalObservationDraft draft;
        try {
            draft = parseModelResponse(response == null ? null : response.rawResponse(), command);
        } catch (InvalidModelResponse exception) {
            fail(running, "INVALID_MODEL_RESPONSE", exception.getMessage());
            throw exception;
        }
        String structuredJson = writeJson(draft);
        Instant completedAt = Instant.now();
        AiResult result = new AiResult(UUID.randomUUID().toString(), taskId, RESULT_TYPE,
                response.rawResponse(), structuredJson, SCHEMA_VERSION, completedAt);
        AiTask succeeded = new AiTask(taskId, user.id(), command.campusId(), pending.taskType(),
                AiTaskStatus.SUCCEEDED, pending.modelProvider(), pending.modelName(), pending.promptVersion(),
                pending.inputSummary(), pending.createdAt(), running.startedAt(), completedAt, null,
                null, null, null, null, 1);
        repository.complete(succeeded, result);
        return new AiTaskView(new AiTask(succeeded.id(), succeeded.userId(), succeeded.campusId(),
                succeeded.taskType(), succeeded.status(), succeeded.modelProvider(), succeeded.modelName(),
                succeeded.promptVersion(), succeeded.inputSummary(), succeeded.createdAt(), succeeded.startedAt(),
                succeeded.completedAt(), null, null, null, null, null, 2), draft, null, null);
    }

    public AiTaskView getOwned(String taskId) {
        IdentityGateway.CurrentUser user = authorization.requireActiveUser();
        return toOwnedView(taskId, user.id());
    }

    public AiTaskView confirm(String taskId, AnimalObservationDraft confirmedDraft) {
        IdentityGateway.CurrentUser user = authorization.requireActiveUser();
        AiTaskBundle bundle = ownedBundle(taskId, user.id());
        if (bundle.task().status() != AiTaskStatus.SUCCEEDED || bundle.result() == null) {
            throw new Conflict("只有解析成功的草稿可以确认");
        }
        if (bundle.confirmation() != null || bundle.task().confirmedAt() != null) {
            throw new Conflict("该草稿已经确认，不能重复确认");
        }
        validator.validate(confirmedDraft);
        AnimalObservationDraft original = readDraft(bundle.result().structuredJson());
        String confirmedJson = writeJson(confirmedDraft);
        boolean modified = !Objects.equals(bundle.result().structuredJson(), confirmedJson);
        Instant confirmedAt = Instant.now();
        AiConfirmation confirmation = new AiConfirmation(UUID.randomUUID().toString(), taskId,
                bundle.result().id(), confirmedJson, modified, user.id(), confirmedAt);
        AiTask confirmedTask = new AiTask(bundle.task().id(), bundle.task().userId(), bundle.task().campusId(),
                bundle.task().taskType(), bundle.task().status(), bundle.task().modelProvider(),
                bundle.task().modelName(), bundle.task().promptVersion(), bundle.task().inputSummary(),
                bundle.task().createdAt(), bundle.task().startedAt(), bundle.task().completedAt(),
                bundle.task().failedAt(), bundle.task().errorCode(), bundle.task().errorMessage(),
                confirmedAt, user.id(), bundle.task().version());
        repository.confirm(confirmedTask, confirmation);
        return new AiTaskView(new AiTask(confirmedTask.id(), confirmedTask.userId(), confirmedTask.campusId(),
                confirmedTask.taskType(), confirmedTask.status(), confirmedTask.modelProvider(),
                confirmedTask.modelName(), confirmedTask.promptVersion(), confirmedTask.inputSummary(),
                confirmedTask.createdAt(), confirmedTask.startedAt(), confirmedTask.completedAt(),
                confirmedTask.failedAt(), confirmedTask.errorCode(), confirmedTask.errorMessage(),
                confirmedAt, user.id(), confirmedTask.version() + 1), original, confirmedDraft, modified);
    }

    private AiTaskView toOwnedView(String taskId, String userId) {
        AiTaskBundle bundle = ownedBundle(taskId, userId);
        return new AiTaskView(bundle.task(),
                bundle.result() == null ? null : readDraft(bundle.result().structuredJson()),
                bundle.confirmation() == null ? null : readDraft(bundle.confirmation().confirmedStructuredJson()),
                bundle.confirmation() == null ? null : bundle.confirmation().wasModified());
    }

    private AiTaskBundle ownedBundle(String taskId, String userId) {
        AiTaskBundle bundle = repository.findBundle(taskId)
                .orElseThrow(() -> new NotFound("AI 任务不存在"));
        if (!bundle.task().userId().equals(userId)) throw new NotFound("AI 任务不存在");
        return bundle;
    }

    private AnimalObservationDraft parseModelResponse(String raw, ObservationParseCommand command) {
        if (raw == null || raw.isBlank()) throw new InvalidModelResponse("模型返回为空");
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new InvalidModelResponse("模型未返回严格 JSON 对象");
        }
        try {
            AnimalObservationDraft draft = strictMapper.readValue(trimmed, AnimalObservationDraft.class);
            validator.validate(draft);
            if (!Objects.equals(command.locationDescription(), draft.locationDescription())) {
                throw new InvalidModelResponse("模型不得推断或改写位置");
            }
            if (!Objects.equals(command.occurredAt(), draft.occurredAt())) {
                throw new InvalidModelResponse("模型不得推断或改写发生时间");
            }
            return draft;
        } catch (InvalidModelResponse exception) {
            throw exception;
        } catch (JsonProcessingException | ValidationException exception) {
            throw new InvalidModelResponse("模型 JSON 不符合结构化 Schema", exception);
        }
    }

    private void fail(AiTask running, String code, String message) {
        repository.fail(new AiTask(running.id(), running.userId(), running.campusId(), running.taskType(),
                AiTaskStatus.FAILED, running.modelProvider(), running.modelName(), running.promptVersion(),
                running.inputSummary(), running.createdAt(), running.startedAt(), null, Instant.now(),
                code, message, null, null, running.version()));
    }

    private String inputSummary(ObservationParseCommand command) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "mediaCount", command.mediaObjectKeys().size(),
                    "hasText", command.text() != null && !command.text().isBlank(),
                    "hasLocationDescription", command.locationDescription() != null,
                    "hasOccurredAt", command.occurredAt() != null));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 AI 输入摘要", exception);
        }
    }

    private String writeJson(AnimalObservationDraft value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化结构化草稿", exception); }
    }

    private AnimalObservationDraft readDraft(String value) {
        try { return objectMapper.readValue(value, AnimalObservationDraft.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("已持久化草稿无法读取", exception); }
    }
}
