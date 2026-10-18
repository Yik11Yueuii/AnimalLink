package org.animallink.animal.application;

import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalMergeRecord;
import org.animallink.animal.domain.AnimalMergeRepository;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.IdentityStatus;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.domain.StateConflictException;
import org.springframework.stereotype.Component;

@Component
public class AnimalCanonicalResolver {
    private static final String PUBLIC_UNAVAILABLE = "Animal 不存在或不可公开访问";

    private final AnimalRepository animalRepository;
    private final AnimalMergeRepository mergeRepository;

    public AnimalCanonicalResolver(AnimalRepository animalRepository,
                                   AnimalMergeRepository mergeRepository) {
        this.animalRepository = animalRepository;
        this.mergeRepository = mergeRepository;
    }

    public CanonicalAnimal resolvePublic(String requestedAnimalId) {
        Animal requested = animalRepository.findById(requestedAnimalId)
                .orElseThrow(this::publicUnavailable);
        if (requested.identityStatus() == IdentityStatus.ACTIVE) {
            return new CanonicalAnimal(requestedAnimalId, requestedAnimalId, requested, false);
        }
        if (requested.identityStatus() != IdentityStatus.MERGED) {
            throw publicUnavailable();
        }

        AnimalMergeRecord merge = mergeRepository.findBySource(requestedAnimalId)
                .orElseThrow(this::publicUnavailable);
        Animal canonical = animalRepository.findById(merge.targetAnimalId())
                .orElseThrow(this::publicUnavailable);
        if (canonical.identityStatus() != IdentityStatus.ACTIVE) {
            throw publicUnavailable();
        }
        return new CanonicalAnimal(requestedAnimalId, canonical.id(), canonical, true);
    }

    public GovernanceAnimalDetail resolveGovernance(Animal requested) {
        if (requested.identityStatus() != IdentityStatus.MERGED) {
            return new GovernanceAnimalDetail(requested, requested.id(), null, null);
        }
        AnimalMergeRecord merge = mergeRepository.findBySource(requested.id())
                .orElseThrow(() -> new StateConflictException(
                        "MERGED Animal 缺少 animal_merge_record"));
        Animal canonical = animalRepository.findById(merge.targetAnimalId())
                .orElseThrow(() -> new StateConflictException("Animal Merge target 不存在"));
        if (canonical.identityStatus() != IdentityStatus.ACTIVE) {
            throw new StateConflictException("Animal Merge canonical target 状态异常");
        }
        return new GovernanceAnimalDetail(requested, canonical.id(), merge.id(), merge.createdAt());
    }

    private ResourceNotFoundException publicUnavailable() {
        return new ResourceNotFoundException(PUBLIC_UNAVAILABLE);
    }
}
