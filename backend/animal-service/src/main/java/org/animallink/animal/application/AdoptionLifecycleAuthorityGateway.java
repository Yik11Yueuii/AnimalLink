package org.animallink.animal.application;

import java.time.Instant;

public interface AdoptionLifecycleAuthorityGateway {
    Authority latest(String animalId);

    record Authority(boolean exists, String animalId, String handoverId,
                     String handoverStatus, Instant lifecycleStartedAt,
                     String relationId, String relationStatus,
                     Instant relationActivatedAt, Instant relationEndedAt) {
    }
}
