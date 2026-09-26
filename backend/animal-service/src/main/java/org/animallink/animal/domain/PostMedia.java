package org.animallink.animal.domain;

import java.time.Instant;

public record PostMedia(
        String id,
        String postId,
        String objectKey,
        String contentType,
        MediaType mediaType,
        Long sizeBytes,
        int sortOrder,
        Visibility visibility,
        Instant createdAt) {
}
