package org.animallink.animal.infrastructure;

import java.util.List;
import org.animallink.animal.application.AdoptionMergeEligibilityGateway;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpAdoptionMergeEligibilityGateway implements AdoptionMergeEligibilityGateway {
    private final RestClient restClient;

    public HttpAdoptionMergeEligibilityGateway(
            @Qualifier("adoptionRestClient") RestClient adoptionRestClient) {
        this.restClient = adoptionRestClient;
    }

    @Override
    public Eligibility check(String sourceAnimalId, String targetAnimalId) {
        return request("/internal/v1/adoption-merge-eligibility/check",
                new EligibilityRequest(sourceAnimalId, targetAnimalId));
    }

    @Override
    public Eligibility checkAnimal(String animalId) {
        return request("/internal/v1/adoption-merge-eligibility/check-animal",
                new AnimalEligibilityRequest(animalId));
    }

    private Eligibility request(String uri, Object request) {
        try {
            EligibilityPayload payload = restClient.post()
                    .uri(uri)
                    .header("X-Internal-Service", "animal-service")
                    .header("X-Trace-Id", traceId())
                    .body(request)
                    .retrieve()
                    .body(EligibilityPayload.class);
            if (payload == null || payload.eligible() == null || payload.blockers() == null
                    || payload.eligible() != payload.blockers().isEmpty()) {
                throw unavailable();
            }
            return new Eligibility(payload.eligible());
        } catch (DependencyUnavailableException exception) {
            throw exception;
        } catch (ResourceAccessException | RestClientResponseException
                 | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException(
                "adoption-service 暂不可用，Animal Merge 已拒绝");
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "animal-service" : traceId;
    }

    private record EligibilityRequest(String sourceAnimalId, String targetAnimalId) {
    }

    private record AnimalEligibilityRequest(String animalId) {
    }

    private record EligibilityPayload(Boolean eligible, List<BlockerPayload> blockers) {
    }

    private record BlockerPayload(String animalId, String blockerCode) {
    }
}
