package org.animallink.animal.application;

import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.TimelineEntry;
import org.springframework.stereotype.Service;

@Service
public class AnimalQueryService {
    private static final int MAX_PAGE_SIZE = 100;
    private final AnimalRepository repository;
    private final AnimalCanonicalResolver canonicalResolver;

    public AnimalQueryService(AnimalRepository repository,
                              AnimalCanonicalResolver canonicalResolver) {
        this.repository = repository;
        this.canonicalResolver = canonicalResolver;
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

    public PageResult<Animal> searchPublic(String campusId, String query, AnimalSpecies species,
                                           int page, int size) {
        String validCampusId = IdRules.requireUuid(campusId, "campusId");
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String normalizedQuery = query == null ? "" : query.trim();
        return new PageResult<>(
                repository.searchPublicActive(validCampusId, normalizedQuery, species,
                        safeSize, safePage * safeSize),
                safePage, safeSize,
                repository.countPublicActive(validCampusId, normalizedQuery, species));
    }

    public Animal publicProfile(String animalId) {
        String validId = IdRules.requireUuid(animalId, "animalId");
        return canonicalResolver.resolvePublic(validId).animal();
    }

    public PublicAnimalDetail detail(String animalId) {
        String validId = IdRules.requireUuid(animalId, "animalId");
        CanonicalAnimal canonical = canonicalResolver.resolvePublic(validId);
        return new PublicAnimalDetail(validId, canonical.canonicalAnimalId(), canonical.animal(),
                repository.findPublicMedia(canonical.canonicalAnimalId()));
    }

    public PageResult<TimelineEntry> timeline(String animalId, int page, int size) {
        String validId = IdRules.requireUuid(animalId, "animalId");
        CanonicalAnimal canonical = canonicalResolver.resolvePublic(validId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new PageResult<>(repository.findPublicTimeline(canonical.canonicalAnimalId(),
                safeSize, safePage * safeSize), safePage, safeSize,
                repository.countPublicTimeline(canonical.canonicalAnimalId()));
    }
}
