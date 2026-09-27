package org.animallink.intelligence.domain;

import java.time.Instant;

public record MatchingDecision(
        String id,
        String matchingRecordId,
        String userId,
        MatchingDecisionType decisionType,
        String selectedAnimalId,
        Integer selectedRank,
        Double selectedScore,
        String postId,
        String proposalId,
        Instant decidedAt) {
}
