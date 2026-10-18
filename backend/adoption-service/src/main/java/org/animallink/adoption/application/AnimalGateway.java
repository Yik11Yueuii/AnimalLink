package org.animallink.adoption.application;
public interface AnimalGateway {
    AnimalFact requireAnimal(String animalId);
    record AnimalFact(String id, String canonicalAnimalId, String adoptionStatus) {
        public AnimalFact(String id, String adoptionStatus) {
            this(id, id, adoptionStatus);
        }

        public String resolvedCanonicalId() {
            return canonicalAnimalId == null || canonicalAnimalId.isBlank() ? id : canonicalAnimalId;
        }

        public boolean isEligibleForListing() {
            return "OPEN".equals(adoptionStatus);
        }
    }
}
