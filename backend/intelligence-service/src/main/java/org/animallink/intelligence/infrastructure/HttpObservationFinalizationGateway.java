package org.animallink.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.application.ObservationFinalizationGateway;
import org.animallink.intelligence.domain.ApiExceptions.AnimalFinalizationRejected;
import org.animallink.intelligence.domain.ApiExceptions.FinalizationServiceUnavailable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpObservationFinalizationGateway implements ObservationFinalizationGateway {
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public HttpObservationFinalizationGateway(
            @Qualifier("animalRestClient") RestClient client,
            ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public FinalizationResult finalizeObservation(FinalizationCommand command) {
        try {
            FinalizationResult response = client.post()
                    .uri("/internal/v1/observation-finalizations")
                    .header("X-Internal-Service", "intelligence-service")
                    .header("X-Trace-Id", traceId())
                    .body(command)
                    .retrieve()
                    .body(FinalizationResult.class);
            if (response == null || response.postId() == null) {
                throw new FinalizationServiceUnavailable(
                        "animal-service 返回无效的正式记录结果", null);
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw unavailable(exception);
        } catch (RestClientResponseException exception) {
            ErrorPayload payload = readError(exception);
            throw new AnimalFinalizationRejected(exception.getStatusCode().value(),
                    payload.code(), payload.message());
        }
    }

    private ErrorPayload readError(RestClientResponseException exception) {
        try {
            JsonNode value = objectMapper.readTree(exception.getResponseBodyAsString());
            String code = value.path("code").asText("ANIMAL_FINALIZATION_REJECTED");
            String message = value.path("message").asText("animal-service 拒绝正式记录请求");
            return new ErrorPayload(code, message);
        } catch (Exception ignored) {
            return new ErrorPayload("ANIMAL_FINALIZATION_REJECTED",
                    "animal-service 拒绝正式记录请求");
        }
    }

    private FinalizationServiceUnavailable unavailable(Throwable cause) {
        return new FinalizationServiceUnavailable("animal-service 暂不可用，正式记录未完成", cause);
    }

    private String traceId() {
        String value = MDC.get("traceId");
        return value == null ? "intelligence-service" : value;
    }

    private record ErrorPayload(String code, String message) {
    }
}
