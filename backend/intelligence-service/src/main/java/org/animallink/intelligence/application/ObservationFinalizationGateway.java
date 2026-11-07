package org.animallink.intelligence.application;

import org.animallink.intelligence.domain.AnimalObservationDraft;
import org.animallink.intelligence.domain.MatchingDecisionType;

import java.util.List;

public interface ObservationFinalizationGateway {
    long registerAttempt(AttemptCommand command);

    void retireAttempt(RetireCommand command);

    FinalizationResult finalizeObservation(FinalizationCommand command);

    record AttemptCommand(String userId, String campusId, String sourceAiTaskId,
                          String sourceMatchingRecordId, String attemptId,
                          MatchingDecisionType decisionType, String selectedAnimalId) { }

    record RetireCommand(String userId, String campusId, String sourceAiTaskId,
                         String sourceMatchingRecordId, String attemptId, long fencingVersion,
                         MatchingDecisionType decisionType, String selectedAnimalId,
                         String failureCode) { }

    record FinalizationCommand(
            String userId,
            String campusId,
            String sourceAiTaskId,
            String sourceMatchingRecordId,
            String attemptId,
            long fencingVersion,
            MatchingDecisionType decisionType,
            String selectedAnimalId,
            String postText,
            List<String> mediaObjectKeys,
            AnimalObservationDraft confirmedDraft) {
    }

    record FinalizationResult(String postId, String proposalId, String animalId) {
    }
}
