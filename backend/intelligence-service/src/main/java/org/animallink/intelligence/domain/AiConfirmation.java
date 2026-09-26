package org.animallink.intelligence.domain;

import java.time.Instant;

public record AiConfirmation(String id, String taskId, String resultId,
                             String confirmedStructuredJson, boolean wasModified,
                             String confirmedBy, Instant confirmedAt) {
}
