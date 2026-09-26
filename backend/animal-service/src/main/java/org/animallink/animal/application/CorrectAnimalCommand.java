package org.animallink.animal.application;

import org.animallink.animal.domain.AnimalSex;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.SterilizationStatus;

public record CorrectAnimalCommand(
        String displayName,
        AnimalSpecies species,
        AnimalSex sex,
        String coatColor,
        String distinctiveFeatures,
        String description,
        SterilizationStatus sterilizationStatus,
        String typicalArea) {

    public boolean isEmpty() {
        return displayName == null && species == null && sex == null && coatColor == null
                && distinctiveFeatures == null && description == null
                && sterilizationStatus == null && typicalArea == null;
    }
}
