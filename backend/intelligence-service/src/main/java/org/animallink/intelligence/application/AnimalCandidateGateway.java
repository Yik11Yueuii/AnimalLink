package org.animallink.intelligence.application;

import java.time.Instant;
import java.util.List;

public interface AnimalCandidateGateway {
    List<CandidateSnapshot> recall(String campusId, String species, int limit);

    record CandidateSnapshot(
            String id, String campusId, String displayName, String species, String sex,
            String coatColor, String distinctiveFeatures, String typicalArea,
            List<PublicMedia> media, Instant lastSeenAt, String recentSummary) {
    }

    record PublicMedia(String id, String objectKey, String contentType) {
    }
}
