package org.animallink.animal.domain;

public record GovernanceCommentView(
        Comment comment,
        String campusId,
        PostStatus parentPostStatus) {
}
