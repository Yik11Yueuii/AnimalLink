package org.animallink.animal.application;

import jakarta.validation.ValidationException;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnimalApplicationService {
    private final AnimalRepository repository;
    private final IdentityGateway identityGateway;
    private final GovernanceAuthorization authorization;
    private final TransactionTemplate transactions;

    public AnimalApplicationService(AnimalRepository repository, IdentityGateway identityGateway,
                                    GovernanceAuthorization authorization,
                                    TransactionTemplate transactions) {
        this.repository = repository;
        this.identityGateway = identityGateway;
        this.authorization = authorization;
        this.transactions = transactions;
    }

    public Animal create(CreateAnimalCommand command) {
        authorization.requireAdministrator();
        String campusId = IdRules.requireUuid(command.campusId(), "campusId");
        identityGateway.requireActiveCampus(campusId);
        Animal animal = Animal.create(campusId, command.displayName(), command.species(), command.sex(),
                command.coatColor(), command.distinctiveFeatures(), command.description(),
                command.sterilizationStatus(), command.typicalArea());
        return transactions.execute(status -> {
            repository.insert(animal);
            return repository.findById(animal.id()).orElseThrow();
        });
    }

    public Animal correct(String animalId, CorrectAnimalCommand command) {
        authorization.requireAdministrator();
        if (command.isEmpty()) {
            throw new ValidationException("至少提供一个可修正字段");
        }
        String validId = IdRules.requireUuid(animalId, "animalId");
        return transactions.execute(status -> {
            Animal existing = findAny(validId);
            Animal corrected = existing.correct(command.displayName(), command.species(), command.sex(),
                    command.coatColor(), command.distinctiveFeatures(), command.description(),
                    command.sterilizationStatus(), command.typicalArea());
            repository.update(corrected);
            return findAny(validId);
        });
    }

    public Animal archive(String animalId) {
        authorization.requireAdministrator();
        String validId = IdRules.requireUuid(animalId, "animalId");
        return transactions.execute(status -> {
            Animal archived = findAny(validId).archive();
            repository.update(archived);
            return findAny(validId);
        });
    }

    private Animal findAny(String animalId) {
        return repository.findById(animalId)
                .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在"));
    }
}
