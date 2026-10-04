package org.animallink.adoption.domain;

import java.time.Instant;
import java.util.UUID;

public record AdoptionSelection(String id, String listingId, String applicationId, SelectionStatus status,
                                String selectedByUserId, Instant selectedAt, String note,
                                String cancelledByUserId, Instant cancelledAt, String cancelReason) {
    public static AdoptionSelection active(String listingId, String applicationId, String selectedByUserId, String note) {
        return new AdoptionSelection(UUID.randomUUID().toString(), listingId, applicationId, SelectionStatus.ACTIVE,
                selectedByUserId, Instant.now(), note, null, null, null);
    }
}
