package org.animallink.animal.domain;

import java.util.Optional;

public interface AnimalAdoptionStatusCommandRepository {
    Optional<AnimalAdoptionStatusCommand> findByKey(String idempotencyKey);

    Optional<AnimalAdoptionStatusCommand> findByKeyForUpdate(String idempotencyKey);

    void insert(AnimalAdoptionStatusCommand command);
}
