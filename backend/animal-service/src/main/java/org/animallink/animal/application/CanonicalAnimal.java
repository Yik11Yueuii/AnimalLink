package org.animallink.animal.application;

import org.animallink.animal.domain.Animal;

public record CanonicalAnimal(
        String requestedAnimalId,
        String canonicalAnimalId,
        Animal animal,
        boolean resolved) {
}
