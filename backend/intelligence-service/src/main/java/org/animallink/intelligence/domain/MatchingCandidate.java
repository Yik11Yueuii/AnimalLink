package org.animallink.intelligence.domain;

import java.util.List;

public record MatchingCandidate(
        int rank, String animalId, String displayName, String species,
        String coverMediaObjectKey, Double imageScore, Double traitScore,
        Double geoScore, Double historyScore, double finalScore,
        List<String> reasons, List<String> missingDimensions) {
}
