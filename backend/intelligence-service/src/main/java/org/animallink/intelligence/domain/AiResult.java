package org.animallink.intelligence.domain;

import java.time.Instant;

public record AiResult(String id, String taskId, String resultType, String rawResponse,
                       String structuredJson, String schemaVersion, Instant createdAt) {
}
