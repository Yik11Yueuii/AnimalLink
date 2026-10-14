package org.animallink.animal.domain;

import java.util.List;

public record GovernancePostView(
        Post post,
        long likeCount,
        long activeCommentCount,
        long hiddenCommentCount,
        List<PostMedia> media) {
}
