package org.animallink.adoption.application;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdoptionLifecycleAuthorityService {
    private final AdoptionLifecycleAuthorityRepository repository;

    public AdoptionLifecycleAuthorityService(AdoptionLifecycleAuthorityRepository repository) {
        this.repository = repository;
    }

    public Result latest(String animalId) {
        String normalized = uuid(animalId);
        return repository.findLatestByAnimalId(normalized)
                .map(value -> new Result(true, value.animalId(), value.handoverId(),
                        value.handoverStatus(), value.lifecycleStartedAt(),
                        value.relationId(), value.relationStatus(),
                        value.relationActivatedAt(), value.relationEndedAt()))
                .orElseGet(() -> new Result(false, normalized, null, null,
                        null, null, null, null, null));
    }

    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("animalId 必须是 UUID");
        }
    }

    public record Result(boolean exists, String animalId, String handoverId,
                         String handoverStatus, java.time.Instant lifecycleStartedAt,
                         String relationId, String relationStatus,
                         java.time.Instant relationActivatedAt,
                         java.time.Instant relationEndedAt) {
    }
}
