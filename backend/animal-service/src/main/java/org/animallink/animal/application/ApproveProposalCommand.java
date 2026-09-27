package org.animallink.animal.application;

import org.animallink.animal.domain.AnimalSex;
import org.animallink.animal.domain.AnimalSpecies;

public record ApproveProposalCommand(
        String displayName,
        AnimalSpecies species,
        AnimalSex sex,
        String coatColor,
        String distinctiveFeatures,
        String description,
        String typicalArea) {
}
