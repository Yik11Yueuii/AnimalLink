package org.animallink.adoption.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionMergeEligibilityService {
    private final AdoptionMergeEligibilityRepository repository;

    public AdoptionMergeEligibilityService(AdoptionMergeEligibilityRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Result check(String sourceAnimalId, String targetAnimalId) {
        String source = validUuid(sourceAnimalId, "sourceAnimalId");
        String target = validUuid(targetAnimalId, "targetAnimalId");
        if (source.equals(target)) {
            throw new IllegalArgumentException("sourceAnimalId 与 targetAnimalId 不能相同");
        }
        List<AdoptionMergeEligibilityRepository.Blocker> blockers = repository.findBlockers(source, target)
                .stream()
                .sorted(Comparator.comparingInt((AdoptionMergeEligibilityRepository.Blocker blocker) ->
                                blocker.animalId().equals(source) ? 0 : 1)
                        .thenComparing(blocker -> blocker.blockerCode().ordinal()))
                .toList();
        return new Result(blockers.isEmpty(), blockers);
    }

    @Transactional(readOnly = true)
    public Result checkAnimal(String animalId) {
        String validAnimalId = validUuid(animalId, "animalId");
        List<AdoptionMergeEligibilityRepository.Blocker> blockers = repository
                .findBlockers(validAnimalId).stream()
                .sorted(Comparator.comparing(blocker -> blocker.blockerCode().ordinal()))
                .toList();
        return new Result(blockers.isEmpty(), blockers);
    }

    private static String validUuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " 必须是 UUID");
        }
    }

    public record Result(boolean eligible, List<AdoptionMergeEligibilityRepository.Blocker> blockers) {
    }
}
