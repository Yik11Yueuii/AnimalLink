package org.animallink.animal.application;

public interface AdoptionMergeEligibilityGateway {
    Eligibility check(String sourceAnimalId, String targetAnimalId);

    Eligibility checkAnimal(String animalId);

    record Eligibility(boolean eligible) {
    }
}
