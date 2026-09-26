package org.animallink.animal.domain;

import java.time.Instant;

public record AnimalMedia(
        String id,
        String animalId,
        String objectKey,
        String contentType,
        MediaType mediaType,
        Long sizeBytes,
        int sortOrder,
        Visibility visibility,
        Instant createdAt) {
}
