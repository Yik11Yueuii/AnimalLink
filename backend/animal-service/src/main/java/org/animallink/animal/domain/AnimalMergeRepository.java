package org.animallink.animal.domain;

import java.util.List;
import java.util.Optional;

public interface AnimalMergeRepository {
    List<Animal> lockAnimals(String firstAnimalId, String secondAnimalId);

    boolean existsAsSource(String animalId);

    boolean existsAsTarget(String animalId);

    void reassignMedia(String sourceAnimalId, String targetAnimalId);

    void reassignTimeline(String sourceAnimalId, String targetAnimalId);

    void consolidateFollowers(String sourceAnimalId, String targetAnimalId);

    void insert(AnimalMergeRecord record);

    void markSourceMerged(String sourceAnimalId, int expectedVersion);

    Optional<AnimalMergeRecord> findBySource(String sourceAnimalId);

    List<AnimalMergeRecord> findHistory(String animalId, int limit, int offset);

    long countHistory(String animalId);
}
