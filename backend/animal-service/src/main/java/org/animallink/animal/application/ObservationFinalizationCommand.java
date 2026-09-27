package org.animallink.animal.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ObservationFinalizationCommand(
        String userId,
        String campusId,
        String sourceAiTaskId,
        String sourceMatchingRecordId,
        String decisionType,
        String selectedAnimalId,
        String postText,
        List<String> mediaObjectKeys,
        ConfirmedDraft confirmedDraft) {

    public record ConfirmedDraft(
            String species,
            String sex,
            String coatColor,
            List<String> distinctiveFeatures,
            String visibleCondition,
            String behavior,
            Integer estimatedCount,
            Boolean possibleAbnormality,
            List<String> abnormalFlags,
            String locationDescription,
            Instant occurredAt,
            Double confidence,
            Map<String, Double> fieldConfidence,
            List<String> warnings,
            List<String> unknownFields) {
    }
}
