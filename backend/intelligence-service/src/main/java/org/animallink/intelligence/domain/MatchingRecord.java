package org.animallink.intelligence.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MatchingRecord(
        String id, String userId, String aiTaskId, String campusId,
        String algorithmVersion, String weightVersion, MatchingExperiment experiment,
        Map<String, Double> weights, int topK, int candidateCount,
        boolean lowConfidence, Instant createdAt, List<MatchingCandidate> candidates) {
}
