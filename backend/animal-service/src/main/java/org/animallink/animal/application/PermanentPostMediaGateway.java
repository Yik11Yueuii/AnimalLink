package org.animallink.animal.application;

import org.animallink.animal.domain.MediaType;

import java.util.List;

public interface PermanentPostMediaGateway {
    List<PermanentMedia> copyObservationMedia(List<String> sourceObjectKeys, String postId);

    record PermanentMedia(
            String objectKey,
            String contentType,
            MediaType mediaType,
            long sizeBytes,
            int sortOrder) {
    }
}
