package org.animallink.intelligence.domain;

import java.time.Instant;
import java.util.List;

public record AnimalEmbedding(
        String id, String animalId, String mediaId, String mediaObjectKey,
        String modelProvider, String modelName, String modelVersion,
        List<Double> vector, Instant createdAt, Instant updatedAt) {
}
