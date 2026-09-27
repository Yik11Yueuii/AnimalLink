package org.animallink.intelligence.application;

import org.animallink.intelligence.domain.AnimalObservationDraft;
import org.animallink.intelligence.domain.MatchingDecisionType;

import java.util.List;

public interface ObservationFinalizationGateway {
    FinalizationResult finalizeObservation(FinalizationCommand command);

    record FinalizationCommand(
            String userId,
            String campusId,
            String sourceAiTaskId,
            String sourceMatchingRecordId,
            MatchingDecisionType decisionType,
            String selectedAnimalId,
            String postText,
            List<String> mediaObjectKeys,
            AnimalObservationDraft confirmedDraft) {
    }

    record FinalizationResult(String postId, String proposalId, String animalId) {
    }
}
