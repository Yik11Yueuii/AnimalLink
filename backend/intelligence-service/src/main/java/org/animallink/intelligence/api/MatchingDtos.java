package org.animallink.intelligence.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.animallink.intelligence.domain.MatchingCandidate;
import org.animallink.intelligence.domain.MatchingExperiment;
import org.animallink.intelligence.domain.MatchingRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MatchingDtos {
    private MatchingDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MatchRequest(@Min(1) @Max(10) Integer topK, MatchingExperiment experimentCode) {
    }

    public record MatchResponse(
            String matchingRecordId,
            String aiTaskId,
            String campusId,
            String algorithmVersion,
            String weightVersion,
            MatchingExperiment experimentCode,
            Map<String, Double> weights,
            int topK,
            int candidateCount,
            boolean lowConfidence,
            String decisionReason,
            List<CandidateResponse> candidates,
            Instant createdAt) {
        static MatchResponse from(MatchingRecord record) {
            String reason = record.candidateCount() == 0 ? "NO_CANDIDATES"
                    : record.lowConfidence() ? "NO_STRONG_MATCH" : null;
            return new MatchResponse(record.id(), record.aiTaskId(), record.campusId(),
                    record.algorithmVersion(), record.weightVersion(), record.experiment(),
                    record.weights(), record.topK(), record.candidateCount(), record.lowConfidence(),
                    reason, record.candidates().stream().map(CandidateResponse::from).toList(),
                    record.createdAt());
        }
    }

    public record CandidateResponse(
            int rank,
            String animalId,
            String displayName,
            String species,
            String coverMediaObjectKey,
            Double imageScore,
            Double traitScore,
            Double geoScore,
            Double historyScore,
            double finalScore,
            List<String> reasons,
            List<String> missingDimensions) {
        static CandidateResponse from(MatchingCandidate value) {
            return new CandidateResponse(value.rank(), value.animalId(), value.displayName(),
                    value.species(), value.coverMediaObjectKey(), value.imageScore(),
                    value.traitScore(), value.geoScore(), value.historyScore(), value.finalScore(),
                    value.reasons(), value.missingDimensions());
        }
    }
}
