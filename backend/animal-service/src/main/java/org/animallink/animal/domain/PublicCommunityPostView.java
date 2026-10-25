package org.animallink.animal.domain;

import java.time.Instant;

public record PublicCommunityPostView(
        String id,
        String campusId,
        PostType postType,
        String textContent,
        String animalId,
        String animalDisplayName,
        AnimalSpecies animalSpecies,
        AdoptionStatus animalAdoptionStatus,
        CurrentContext animalCurrentContext,
        long likeCount,
        long commentCount,
        Instant createdAt,
        Instant updatedAt) {
}
