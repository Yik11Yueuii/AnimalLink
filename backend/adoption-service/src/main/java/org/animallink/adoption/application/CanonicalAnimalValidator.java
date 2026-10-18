package org.animallink.adoption.application;

import org.animallink.adoption.domain.AnimalNotEligibleException;
import org.springframework.stereotype.Service;

@Service
public class CanonicalAnimalValidator {
    private final AnimalGateway animalGateway;

    public CanonicalAnimalValidator(AnimalGateway animalGateway) {
        this.animalGateway = animalGateway;
    }

    public AnimalGateway.AnimalFact requireCanonical(String requestedAnimalId) {
        AnimalGateway.AnimalFact animal = animalGateway.requireAnimal(requestedAnimalId);
        if (!requestedAnimalId.equals(animal.resolvedCanonicalId())) {
            throw new AnimalNotEligibleException("Animal 已归并，请使用 canonical Animal ID 重新提交");
        }
        return animal;
    }

    public AnimalGateway.AnimalFact requireCanonicalEligibleForListing(String requestedAnimalId) {
        AnimalGateway.AnimalFact animal = requireCanonical(requestedAnimalId);
        if (!animal.isEligibleForListing()) {
            throw new AnimalNotEligibleException("当前动物状态不允许发布领养信息");
        }
        return animal;
    }
}
