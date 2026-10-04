package org.animallink.adoption.infrastructure;

import org.animallink.adoption.application.AnimalGateway;
import org.animallink.adoption.domain.AnimalNotFoundException;
import org.animallink.adoption.domain.DependencyUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpAnimalGateway implements AnimalGateway {
    private final RestClient client;
    public HttpAnimalGateway(@Qualifier("animalRestClient") RestClient client) { this.client = client; }
    public AnimalFact requireAnimal(String animalId) {
        try {
            AnimalPayload payload = client.get().uri("/api/v1/animals/{animalId}", animalId).retrieve().body(AnimalPayload.class);
            if (payload == null || payload.id() == null) throw new AnimalNotFoundException("Animal 不存在");
            return new AnimalFact(payload.id(), payload.adoptionStatus());
        } catch (HttpClientErrorException.NotFound e) { throw new AnimalNotFoundException("Animal 不存在");
        } catch (AnimalNotFoundException e) { throw e;
        } catch (ResourceAccessException | RestClientResponseException e) { throw new DependencyUnavailableException("animal-service 暂不可用"); }
    }
    private record AnimalPayload(String id, String adoptionStatus) { }
}
