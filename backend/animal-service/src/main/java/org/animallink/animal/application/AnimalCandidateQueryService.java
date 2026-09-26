package org.animallink.animal.application;

import org.animallink.animal.domain.AnimalCandidateSnapshot;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.AnimalSpecies;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnimalCandidateQueryService {
    private final AnimalRepository repository;

    public AnimalCandidateQueryService(AnimalRepository repository) {
        this.repository = repository;
    }

    public List<AnimalCandidateSnapshot> recall(String campusId, String species, int limit) {
        if (campusId == null || campusId.isBlank()) throw new IllegalArgumentException("campusId 不能为空");
        if (limit < 20 || limit > 100) throw new IllegalArgumentException("limit 必须在 20 到 100 之间");
        AnimalSpecies constraint = null;
        if (species != null && !species.isBlank() && !"UNKNOWN".equalsIgnoreCase(species)) {
            try {
                constraint = AnimalSpecies.valueOf(species.toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("species 必须是 CAT、DOG、OTHER 或 UNKNOWN");
            }
        }
        return repository.recallCandidates(campusId.trim(), constraint, limit);
    }
}
