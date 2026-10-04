package org.animallink.adoption.application;
public interface AnimalGateway {
    AnimalFact requireAnimal(String animalId);
    record AnimalFact(String id, String adoptionStatus) { public boolean isEligibleForListing() { return "OPEN".equals(adoptionStatus); } }
}
