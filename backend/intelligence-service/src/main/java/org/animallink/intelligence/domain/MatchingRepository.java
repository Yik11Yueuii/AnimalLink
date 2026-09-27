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

    void saveDecision(MatchingDecision decision);
}
