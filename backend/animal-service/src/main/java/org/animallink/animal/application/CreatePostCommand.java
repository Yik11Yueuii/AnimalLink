package org.animallink.animal.application;

import org.animallink.animal.domain.MediaType;

import java.util.List;

public record CreatePostCommand(
        String campusId,
        String animalId,
        String textContent,
        List<MediaInput> media) {
    public record MediaInput(String objectKey, String contentType, MediaType mediaType,
                             Long sizeBytes, int sortOrder) {
    }
}
