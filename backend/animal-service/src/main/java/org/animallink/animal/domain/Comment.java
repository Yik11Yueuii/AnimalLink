package org.animallink.animal.domain;

import java.time.Instant;
import java.util.UUID;

public record Comment(
        String id,
        String postId,
        String authorUserId,
        String content,
        CommentStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public static Comment create(String postId, String authorUserId, String content) {
        Instant now = Instant.now();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        return new Comment(UUID.randomUUID().toString(), postId, authorUserId, content.trim(),
                CommentStatus.ACTIVE, now, now);
    }
}
