package org.animallink.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.application.ObservationFinalizationGateway;
import org.animallink.intelligence.domain.ApiExceptions.AnimalFinalizationRejected;
import org.animallink.intelligence.domain.ApiExceptions.FinalizationServiceUnavailable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpObservationFinalizationGateway implements ObservationFinalizationGateway {
    private static final String BASE = "/internal/v1/observation-finalizations";
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final ObservationServiceAssertionSigner assertionSigner;

    public HttpObservationFinalizationGateway(
            @Qualifier("animalRestClient") RestClient client,
            ObjectMapper objectMapper, ObservationServiceAssertionSigner assertionSigner) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.assertionSigner = assertionSigner;
    }

    @Override
    public long registerAttempt(AttemptCommand command) {
        var context = context(command.userId(), command.campusId(), command.sourceAiTaskId(),
                command.sourceMatchingRecordId(), command.attemptId(), 0,
                command.decisionType().name(), command.selectedAnimalId());
        RegisterResponse response = post(BASE + "/attempts/register", "observation:register",
                context, command, RegisterResponse.class);
        if (response == null || response.fencingVersion() < 1)
            throw new FinalizationServiceUnavailable("animal-service 返回无效尝试版本", null);
        return response.fencingVersion();
    }

    @Override
    public void retireAttempt(RetireCommand command) {
        var context = context(command.userId(), command.campusId(), command.sourceAiTaskId(),
                command.sourceMatchingRecordId(), command.attemptId(), command.fencingVersion(),
                command.decisionType().name(), command.selectedAnimalId());
        RetireResponse response = post(BASE + "/attempts/retire", "observation:retire",
                context, command, RetireResponse.class);
        if (response == null || !response.retired())
            throw new FinalizationServiceUnavailable("animal-service 未确认尝试撤销", null);
    }

    @Override
    public FinalizationResult finalizeObservation(FinalizationCommand command) {
        var context = context(command.userId(), command.campusId(), command.sourceAiTaskId(),
                command.sourceMatchingRecordId(), command.attemptId(), command.fencingVersion(),
                command.decisionType().name(), command.selectedAnimalId());
        FinalizationResult response = post(BASE, "observation:finalize", context,
                command, FinalizationResult.class);
        if (response == null || response.postId() == null)
            throw new FinalizationServiceUnavailable("animal-service 返回无效正式记录结果", null);
        return response;
    }

    private <T> T post(String path, String scope, ObservationServiceAssertionSigner.Context context,
                       Object request, Class<T> responseType) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Service", "intelligence-service")
                    .header("Authorization", "Bearer " + assertionSigner.sign(path, scope, context, bytes))
                    .header("X-Trace-Id", traceId())
                    .body(bytes).retrieve().body(responseType);
        } catch (ResourceAccessException exception) {
            throw new FinalizationServiceUnavailable("animal-service 暂不可用，正式记录未完成", exception);
        } catch (RestClientResponseException exception) {
            ErrorPayload payload = readError(exception);
            throw new AnimalFinalizationRejected(exception.getStatusCode().value(),
                    payload.code(), payload.message());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化正式化请求");
        }
    }

    private ErrorPayload readError(RestClientResponseException exception) {
        try {
            JsonNode value = objectMapper.readTree(exception.getResponseBodyAsString());
            String code = value.path("code").asText();
            String safe = switch (code) {
                case "SELECTED_ANIMAL_NOT_FOUND", "SELECTED_ANIMAL_ARCHIVED",
                        "CROSS_CAMPUS_ANIMAL", "OBSERVATION_ATTEMPT_CONFLICT",
                        "FORBIDDEN", "INVALID_REQUEST" -> code;
                default -> "ANIMAL_FINALIZATION_REJECTED";
            };
            return new ErrorPayload(safe, "animal-service 拒绝正式记录请求");
        } catch (Exception ignored) {
            return new ErrorPayload("ANIMAL_FINALIZATION_REJECTED",
                    "animal-service 拒绝正式记录请求");
        }
    }

    private ObservationServiceAssertionSigner.Context context(String actor, String campus,
            String task, String record, String attempt, long version, String decision, String animal) {
        return new ObservationServiceAssertionSigner.Context(actor, task, campus, record,
                attempt, version, decision, animal);
    }

    private String traceId() {
        String value = MDC.get("traceId");
        return value == null ? "intelligence-service" : value;
    }

    private record ErrorPayload(String code, String message) {
    }

    private record RegisterResponse(long fencingVersion) { }
    private record RetireResponse(boolean retired) { }
}
