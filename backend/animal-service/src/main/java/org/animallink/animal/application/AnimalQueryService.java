package org.animallink.animal.application;

import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.domain.TimelineEntry;
import org.springframework.stereotype.Service;

@Service
public class AnimalQueryService {
    private static final int MAX_PAGE_SIZE = 100;
    private final AnimalRepository repository;

    public AnimalQueryService(AnimalRepository repository) {
        this.repository = repository;
    }

    public PageResult<Animal> search(String campusId, String query, AnimalSpecies species,
                                     int page, int size) {
        String validCampusId = IdRules.requireUuid(campusId, "campusId");
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String normalizedQuery = query == null ? "" : query.trim();
        return new PageResult<>(
                repository.searchActive(validCampusId, normalizedQuery, species,
                        safeSize, safePage * safeSize),
                safePage, safeSize,
                repository.countActive(validCampusId, normalizedQuery, species));
    }

    public AnimalDetail detail(String animalId) {
        String validId = IdRules.requireUuid(animalId, "animalId");
        Animal animal = repository.findActiveById(validId)
                .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在或不可公开访问"));
        return new AnimalDetail(animal, repository.findPublicMedia(validId));
    }

    public PageResult<TimelineEntry> timeline(String animalId, int page, int size) {
        String validId = IdRules.requireUuid(animalId, "animalId");
        if (repository.findActiveById(validId).isEmpty()) {
            throw new ResourceNotFoundException("Animal 不存在或不可公开访问");
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new PageResult<>(repository.findPublicTimeline(validId, safeSize, safePage * safeSize),
                safePage, safeSize, repository.countPublicTimeline(validId));
    }
}
