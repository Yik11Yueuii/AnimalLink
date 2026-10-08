package org.animallink.animal.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.animal.application.AdoptionRelationGateway;
import org.animallink.animal.application.IdRules;
import org.animallink.animal.domain.ActiveAdoptionRelationRequiredException;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpAdoptionRelationGateway implements AdoptionRelationGateway {
    private static final String USER_ID_HEADER = "X-User-Id";
    private final RestClient restClient;
    private final HttpServletRequest request;

    public HttpAdoptionRelationGateway(@Qualifier("adoptionRestClient") RestClient adoptionRestClient,
                                       HttpServletRequest request) {
        this.restClient = adoptionRestClient;
        this.request = request;
    }

    @Override
    public String requireActiveRelation(String animalId) {
        try {
            RestClient.RequestHeadersSpec<?> requestSpec = restClient.get()
                    .uri(builder -> builder.path("/internal/v1/adoption-relations/active")
                            .queryParam("animalId", animalId).build())
                    .header("X-Internal-Service", "animal-service")
                    .header("X-Trace-Id", traceId());
            String userId = request.getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) {
                requestSpec = requestSpec.header(USER_ID_HEADER, userId);
            }
            String authorization = request.getHeader("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                requestSpec = requestSpec.header("Authorization", authorization);
            }
            ActiveRelationPayload payload = requestSpec.retrieve().body(ActiveRelationPayload.class);
            if (payload == null) {
                throw unavailable();
            }
            return IdRules.requireUuid(payload.relationId(), "adoption relationId");
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new ActiveAdoptionRelationRequiredException("当前用户没有该 Animal 的有效领养关系");
        } catch (ActiveAdoptionRelationRequiredException | DependencyUnavailableException exception) {
            throw exception;
        } catch (ResourceAccessException | RestClientResponseException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException("adoption-service 暂不可用，领养后动态发布已拒绝");
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "animal-service" : traceId;
    }

    private record ActiveRelationPayload(String relationId) {
    }
}
