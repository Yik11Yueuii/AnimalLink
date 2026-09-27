package org.animallink.intelligence.application;

import org.animallink.intelligence.domain.MatchingDecisionType;

public record FinalizeObservationCommand(
        MatchingDecisionType decisionType,
        String selectedAnimalId,
        String postText) {
}
