package org.animallink.intelligence.domain;

import java.time.Instant;

public record AiTask(
        String id, String userId, String campusId, AiTaskType taskType, AiTaskStatus status,
        String modelProvider, String modelName, String promptVersion, String inputSummary,
        Instant createdAt, Instant startedAt, Instant completedAt, Instant failedAt,
        String errorCode, String errorMessage, Instant confirmedAt, String confirmedBy, long version) {
}
