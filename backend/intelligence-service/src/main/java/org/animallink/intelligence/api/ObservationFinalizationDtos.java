package org.animallink.intelligence.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.animallink.intelligence.application.FinalizeObservationCommand;
import org.animallink.intelligence.domain.MatchingDecision;
import org.animallink.intelligence.domain.MatchingDecisionType;
import org.animallink.intelligence.domain.MatchingRecord;

import java.time.Instant;

public final class ObservationFinalizationDtos {
    private ObservationFinalizationDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FinalizeRequest(
            @NotNull MatchingDecisionType decisionType,
            @Size(max = 36) String selectedAnimalId,
            @Size(max = 2000) String postText) {
        FinalizeObservationCommand toCommand() {
            return new FinalizeObservationCommand(decisionType, selectedAnimalId, postText);
        }
    }

    public record FinalizeResponse(
            DecisionResponse decision,
            CreatedPostResponse createdPost,
            String proposalId) {
        static FinalizeResponse from(MatchingDecision decision, MatchingRecord record) {
            CreatedPostResponse post = decision.postId() == null ? null
                    : new CreatedPostResponse(decision.postId(), decision.selectedAnimalId());
            return new FinalizeResponse(DecisionResponse.from(decision, record), post,
                    decision.proposalId());
        }
    }

    public record DecisionResponse(
            String id,
            String matchingRecordId,
            MatchingDecisionType decisionType,
            String selectedAnimalId,
            Integer selectedRank,
            Double selectedScore,
            Boolean hitAt1,
            Boolean hitAt3,
            Boolean hitAtK,
            Instant decidedAt) {
        static DecisionResponse from(MatchingDecision decision, MatchingRecord record) {
            boolean knownMatch = decision.decisionType() == MatchingDecisionType.SELECT_EXISTING;
            Integer rank = decision.selectedRank();
            return new DecisionResponse(decision.id(), decision.matchingRecordId(),
                    decision.decisionType(), decision.selectedAnimalId(), rank,
                    decision.selectedScore(), knownMatch ? rank == 1 : null,
                    knownMatch ? rank <= 3 : null,
                    knownMatch ? rank <= record.topK() : null, decision.decidedAt());
        }
    }

    public record CreatedPostResponse(String id, String animalId) {
    }
}
