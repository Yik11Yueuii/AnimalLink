package org.animallink.adoption.application;

import java.util.List;

public interface AdoptionMergeEligibilityRepository {
    List<Blocker> findBlockers(String sourceAnimalId, String targetAnimalId);

    List<Blocker> findBlockers(String animalId);

    enum BlockerCode {
        ACTIVE_LISTING,
        ACTIVE_APPLICATION,
        ACTIVE_SELECTION,
        PENDING_HANDOVER,
        ACTIVE_RELATION
    }

    record Blocker(String animalId, BlockerCode blockerCode) {
    }
}
