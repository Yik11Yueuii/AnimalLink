package org.animallink.animal.application;

import java.time.Instant;
import org.animallink.animal.domain.Animal;

public record GovernanceAnimalDetail(
        Animal animal,
        String canonicalAnimalId,
        String mergeId,
        Instant mergedAt) {
}
