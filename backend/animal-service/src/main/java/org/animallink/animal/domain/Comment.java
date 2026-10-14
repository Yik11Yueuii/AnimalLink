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

    public Comment hide() {
        if (status != CommentStatus.ACTIVE) {
            throw new StateConflictException("Comment 已隐藏");
        }
        return new Comment(id, postId, authorUserId, content, CommentStatus.HIDDEN,
                createdAt, Instant.now());
    }

    public Comment restore() {
        if (status != CommentStatus.HIDDEN) {
            throw new StateConflictException("Comment 已处于可见状态");
        }
        return new Comment(id, postId, authorUserId, content, CommentStatus.ACTIVE,
                createdAt, Instant.now());
    }
}
