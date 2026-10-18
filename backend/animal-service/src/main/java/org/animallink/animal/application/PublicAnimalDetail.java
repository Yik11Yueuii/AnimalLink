package org.animallink.animal.application;

import java.util.List;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalMedia;

public record PublicAnimalDetail(
        String requestedAnimalId,
        String canonicalAnimalId,
        Animal animal,
        List<AnimalMedia> media) {
}
