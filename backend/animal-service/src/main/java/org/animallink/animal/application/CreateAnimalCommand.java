package org.animallink.animal.application;

import org.animallink.animal.domain.AnimalSex;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.SterilizationStatus;

public record CreateAnimalCommand(
        String campusId,
        String displayName,
        AnimalSpecies species,
        AnimalSex sex,
        String coatColor,
        String distinctiveFeatures,
        String description,
        SterilizationStatus sterilizationStatus,
        String typicalArea) {
}
