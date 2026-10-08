package org.animallink.animal.domain;

import java.time.Instant;
import java.util.UUID;

public record Post(
        String id,
        String campusId,
        String animalId,
        String relationId,
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
        return observationPost(UUID.randomUUID().toString(), campusId, animalId,
                authorUserId, textContent);
    }

    public static Post observationPost(String id, String campusId, String animalId,
                                       String authorUserId, String textContent) {
        Instant now = Instant.now();
        return new Post(id, campusId, animalId, null, authorUserId,
                PostType.CAMPUS_POST, required(textContent), Visibility.PUBLIC,
                PostStatus.ACTIVE, 0, now, now);
    }

    public static Post postAdoption(String campusId, String animalId, String authorUserId,
                                    String relationId, String textContent) {
        Instant now = Instant.now();
        return new Post(UUID.randomUUID().toString(), campusId, animalId,
                requiredUuid(relationId, "relationId"), authorUserId,
                PostType.POST_ADOPTION, required(textContent), Visibility.PUBLIC,
                PostStatus.ACTIVE, 0, now, now);
    }

    public Post hide() {
        if (status != PostStatus.ACTIVE) {
            throw new StateConflictException("Post 已隐藏");
        }
        return new Post(id, campusId, animalId, relationId, authorUserId, postType, textContent,
                visibility, PostStatus.HIDDEN, version, createdAt, Instant.now());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("textContent 不能为空");
        }
        return value.trim();
    }

    private static String requiredUuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(field + " 必须是合法 UUID");
        }
    }
}
