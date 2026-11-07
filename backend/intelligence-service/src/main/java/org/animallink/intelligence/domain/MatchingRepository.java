package org.animallink.intelligence.domain;

import java.util.Optional;

public interface MatchingRepository {
    Optional<AnimalEmbedding> findEmbedding(String animalId, String mediaId,
                                             String modelName, String modelVersion);

    AnimalEmbedding saveEmbeddingOrLoadExisting(AnimalEmbedding embedding);

    void save(MatchingRecord record);

    Optional<MatchingRecord> findById(String recordId);

    Optional<MatchingRecord> lockById(String recordId);

    Optional<MatchingDecision> findDecisionByRecordId(String recordId);

    Optional<MatchingDecision> findDecisionByAiTaskId(String taskId);

    Optional<MatchingDecisionIntent> findIntentByAiTaskId(String taskId);

    void saveIntent(MatchingDecisionIntent intent);

    void setIntentVersion(String intentId, long version);

    void setIntentFailure(String intentId, String failureCode);

    void retireIntent(MatchingDecisionIntent intent);

    void saveDecision(MatchingDecision decision);
}
