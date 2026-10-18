package org.animallink.animal.domain;

import java.time.Instant;

public record AnimalMergeRecord(
        String id,
        String sourceAnimalId,
        String targetAnimalId,
        String actorUserId,
        String reason,
        Instant createdAt) {
}
