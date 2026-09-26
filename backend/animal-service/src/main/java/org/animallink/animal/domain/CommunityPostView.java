package org.animallink.animal.domain;

public record CommunityPostView(
        Post post,
        String animalDisplayName,
        AnimalSpecies animalSpecies,
        String animalIdentityStatus,
        String animalAdoptionStatus,
        String animalCoverObjectKey,
        long likeCount,
        long commentCount,
        boolean likedByMe) {
}
