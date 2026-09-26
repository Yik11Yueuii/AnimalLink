package org.animallink.animal.domain;

import java.time.Instant;
import java.util.UUID;

public record Post(
        String id,
        String campusId,
        String animalId,
        String authorUserId,
        PostType postType,
        String textContent,
        Visibility visibility,
        PostStatus status,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static Post campusPost(String campusId, String animalId, String authorUserId,
                                  String textContent) {
        Instant now = Instant.now();
        return new Post(UUID.randomUUID().toString(), campusId, animalId, authorUserId,
                PostType.CAMPUS_POST, required(textContent), Visibility.PUBLIC,
                PostStatus.ACTIVE, 0, now, now);
    }

    public Post hide() {
        if (status != PostStatus.ACTIVE) {
            throw new StateConflictException("Post 已隐藏");
        }
        return new Post(id, campusId, animalId, authorUserId, postType, textContent,
                visibility, PostStatus.HIDDEN, version, createdAt, Instant.now());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("textContent 不能为空");
        }
        return value.trim();
    }
}
