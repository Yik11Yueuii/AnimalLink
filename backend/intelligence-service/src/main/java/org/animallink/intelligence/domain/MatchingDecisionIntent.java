package org.animallink.intelligence.domain;

import java.time.Instant;

public record MatchingDecisionIntent(
        String id, String matchingRecordId, String sourceAiTaskId, String userId,
        MatchingDecisionType decisionType, String selectedAnimalId,
        Integer selectedRank, Double selectedScore, String postText, Instant createdAt,
        Long fencingVersion, String deterministicFailureCode) {

    public MatchingDecisionIntent withVersion(long version) {
        return new MatchingDecisionIntent(id, matchingRecordId, sourceAiTaskId, userId,
                decisionType, selectedAnimalId, selectedRank, selectedScore, postText,
                createdAt, version, deterministicFailureCode);
    }

    public MatchingDecisionIntent withFailure(String code) {
        return new MatchingDecisionIntent(id, matchingRecordId, sourceAiTaskId, userId,
                decisionType, selectedAnimalId, selectedRank, selectedScore, postText,
                createdAt, fencingVersion, code);
    }
}
