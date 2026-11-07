package org.animallink.intelligence.application;

import org.animallink.intelligence.domain.AnimalObservationDraft;

import java.time.Instant;
import java.util.List;

/**
 * A user-authored observation used when the AI parser is unavailable.
 * The same validation and CampusMembership boundary applies as to an AI task.
 */
public record ManualObservationCommand(
        String campusId,
        List<String> mediaObjectKeys,
        String text,
        String locationDescription,
        Instant occurredAt,
        AnimalObservationDraft draft) {
}
