package org.animallink.adoption.domain;

import java.time.Instant;
import java.util.UUID;

public record AdoptionApplication(String id, String listingId, String applicantUserId, ApplicationStatus status,
                                  String message, Instant createdAt, Instant updatedAt, Instant withdrawnAt) {
    public static AdoptionApplication submitted(String listingId, String applicantUserId, String message) {
        Instant now = Instant.now();
        return new AdoptionApplication(UUID.randomUUID().toString(), listingId, applicantUserId,
                ApplicationStatus.SUBMITTED, message, now, now, null);
    }
    public AdoptionApplication withdraw() {
        if (status != ApplicationStatus.SUBMITTED) {
            throw new InvalidApplicationTransitionException("只有已提交的申请可以撤回");
        }
        Instant now = Instant.now();
        return new AdoptionApplication(id, listingId, applicantUserId, ApplicationStatus.WITHDRAWN,
                message, createdAt, now, now);
    }
}
