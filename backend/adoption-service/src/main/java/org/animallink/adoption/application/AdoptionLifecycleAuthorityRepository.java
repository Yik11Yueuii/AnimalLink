package org.animallink.adoption.application;

import java.time.Instant;
import java.util.Optional;

public interface AdoptionLifecycleAuthorityRepository {
    Optional<Lifecycle> findLatestByAnimalId(String animalId);

    record Lifecycle(String animalId, String handoverId, String handoverStatus,
                     Instant lifecycleStartedAt, String relationId,
                     String relationStatus, Instant relationActivatedAt,
                     Instant relationEndedAt) {
    }
}
