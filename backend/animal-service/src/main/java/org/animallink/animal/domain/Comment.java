package org.animallink.animal.domain;

import java.time.Instant;
import java.util.UUID;

public record Comment(
        String id,
        String postId,
        String authorUserId,
        String content,
        CommentStatus status,
        PublicReviewStatus reviewStatus,
        String reviewedByUserId,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt) {
    public static Comment create(String postId, String authorUserId, String content) {
        Instant now = Instant.now();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        return new Comment(UUID.randomUUID().toString(), postId, authorUserId, content.trim(),
                CommentStatus.ACTIVE, PublicReviewStatus.PENDING, null, null, now, now);
    }

    public Comment hide() {
        if (status != CommentStatus.ACTIVE) {
            throw new StateConflictException("Comment 已隐藏");
        }
        return new Comment(id, postId, authorUserId, content, CommentStatus.HIDDEN,
                reviewStatus, reviewedByUserId, reviewedAt, createdAt, Instant.now());
    }

    public Comment restore() {
        if (status != CommentStatus.HIDDEN) {
            throw new StateConflictException("Comment 已处于可见状态");
        }
        return new Comment(id, postId, authorUserId, content, CommentStatus.ACTIVE,
                reviewStatus, reviewedByUserId, reviewedAt, createdAt, Instant.now());
    }

    public Comment remove() {
        if (status == CommentStatus.REMOVED) {
            throw new StateConflictException("Comment 已被移除");
        }
        return new Comment(id, postId, authorUserId, content, CommentStatus.REMOVED,
                reviewStatus, reviewedByUserId, reviewedAt, createdAt, Instant.now());
    }

    public Comment review(PublicReviewStatus target, String reviewerUserId, Instant time) {
        if (target == null || target == PublicReviewStatus.PENDING) {
            throw new IllegalArgumentException("审核结果必须是 APPROVED 或 REJECTED");
        }
        if (reviewStatus == target) {
            throw new StateConflictException("Comment 已处于该审核状态");
        }
        String reviewer;
        try {
            reviewer = UUID.fromString(reviewerUserId).toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("reviewerUserId 必须是合法 UUID");
        }
        return new Comment(id, postId, authorUserId, content, status, target, reviewer, time,
                createdAt, Instant.now());
    }
}
