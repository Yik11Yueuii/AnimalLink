package org.animallink.intelligence.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnimalObservationDraft(
        ObservationSpecies species,
        ObservationSex sex,
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
