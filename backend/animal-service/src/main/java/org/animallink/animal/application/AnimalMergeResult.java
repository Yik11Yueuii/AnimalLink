package org.animallink.animal.application;

import java.time.Instant;
import org.animallink.animal.domain.IdentityStatus;

public record AnimalMergeResult(
        String mergeId,
        String sourceAnimalId,
        String targetAnimalId,
        IdentityStatus sourceStatus,
        IdentityStatus targetStatus,
        Instant mergedAt) {
}
