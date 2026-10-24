package org.animallink.animal.domain;

import java.time.Instant;

public record PublicCommunityCommentView(
        String id,
        String postId,
        String content,
        Instant createdAt) {
}
