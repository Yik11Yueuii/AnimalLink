package org.animallink.animal.infrastructure;

import org.animallink.animal.application.AdoptionLifecycleAuthorityGateway;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpAdoptionLifecycleAuthorityGateway
        implements AdoptionLifecycleAuthorityGateway {
    private final RestClient client;

    public HttpAdoptionLifecycleAuthorityGateway(
            @Qualifier("adoptionRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Authority latest(String animalId) {
        try {
            Authority value = client.get()
                    .uri("/internal/v1/adoption-lifecycle/animals/{animalId}", animalId)
                    .header("X-Internal-Service", "animal-service")
                    .retrieve()
                    .body(Authority.class);
            if (value == null || value.animalId() == null
                    || !animalId.equals(value.animalId())) {
                throw unavailable();
            }
            if (value.exists() && (value.handoverId() == null
                    || value.handoverStatus() == null
                    || value.lifecycleStartedAt() == null)) {
                throw unavailable();
            }
            return value;
        } catch (DependencyUnavailableException exception) {
            throw exception;
        } catch (ResourceAccessException | RestClientResponseException exception) {
            throw unavailable();
        }
    }

    private static DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException(
                "adoption-service 领养生命周期权威状态不可用");
    }
}
