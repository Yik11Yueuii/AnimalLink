package org.animallink.animal.domain;

import java.time.Instant;
import java.util.List;

public record AnimalCandidateSnapshot(
        String id,
        String campusId,
        String displayName,
        AnimalSpecies species,
        AnimalSex sex,
        String coatColor,
        String distinctiveFeatures,
        String typicalArea,
        List<PublicMedia> media,
        Instant lastSeenAt,
        String recentSummary) {

    public record PublicMedia(String id, String objectKey, String contentType) {
    }
}
