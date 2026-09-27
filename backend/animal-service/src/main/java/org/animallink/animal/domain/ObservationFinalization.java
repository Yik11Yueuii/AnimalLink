package org.animallink.animal.domain;

import java.time.Instant;

public record ObservationFinalization(
        String id,
        String sourceMatchingRecordId,
        String sourceAiTaskId,
        String userId,
        String campusId,
        String decisionType,
        String selectedAnimalId,
        String postId,
        String proposalId,
        String decisionHash,
        Instant createdAt) {
}
