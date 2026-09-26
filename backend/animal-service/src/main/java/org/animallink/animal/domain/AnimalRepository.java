package org.animallink.animal.domain;

import java.util.List;
import java.util.Optional;

public interface AnimalRepository {
    void insert(Animal animal);

    void update(Animal animal);

    Optional<Animal> findById(String animalId);

    Optional<Animal> findActiveById(String animalId);

    List<Animal> searchActive(String campusId, String query, AnimalSpecies species, int limit, int offset);

    long countActive(String campusId, String query, AnimalSpecies species);

    List<AnimalMedia> findPublicMedia(String animalId);

    List<TimelineEntry> findPublicTimeline(String animalId, int limit, int offset);

    long countPublicTimeline(String animalId);
}
