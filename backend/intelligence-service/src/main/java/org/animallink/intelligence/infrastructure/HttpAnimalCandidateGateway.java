package org.animallink.intelligence.infrastructure;

import org.animallink.intelligence.application.AnimalCandidateGateway;
import org.animallink.intelligence.domain.ApiExceptions.CandidateServiceUnavailable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class HttpAnimalCandidateGateway implements AnimalCandidateGateway {
    private final RestClient client;

    public HttpAnimalCandidateGateway(@Qualifier("animalRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public List<CandidateSnapshot> recall(String campusId, String species, int limit) {
        try {
            BatchResponse response = client.post().uri("/internal/v1/animals/candidates")
                    .body(new BatchRequest(campusId, species, limit)).retrieve().body(BatchResponse.class);
            return response == null || response.candidates() == null ? List.of() : response.candidates();
        } catch (RestClientException exception) {
            throw new CandidateServiceUnavailable("Animal 候选服务暂不可用", exception);
        }
    }

    private record BatchRequest(String campusId, String species, int limit) {
    }

    private record BatchResponse(List<CandidateSnapshot> candidates) {
    }
}
