package org.animallink.adoption.domain;

import java.time.Instant;
import java.util.UUID;

public record AdoptionApplication(String id, String listingId, String applicantUserId, ApplicationStatus status,
                                  String message, Instant createdAt, Instant updatedAt, Instant withdrawnAt,
                                  String reviewerUserId, Instant reviewedAt, String reviewComment) {
    public static AdoptionApplication submitted(String listingId, String applicantUserId, String message) {
        Instant now = Instant.now();
        return new AdoptionApplication(UUID.randomUUID().toString(), listingId, applicantUserId,
                ApplicationStatus.SUBMITTED, message, now, now, null, null, null, null);
    }
    public AdoptionApplication withdraw() {
        if (status != ApplicationStatus.SUBMITTED) {
            throw new InvalidApplicationTransitionException("只有已提交的申请可以撤回");
        }
        Instant now = Instant.now();
        return new AdoptionApplication(id, listingId, applicantUserId, ApplicationStatus.WITHDRAWN,
                message, createdAt, now, now, null, null, null);
    }
    public AdoptionApplication review(ApplicationStatus decision, String reviewerUserId, String reviewComment) {
        if (status != ApplicationStatus.SUBMITTED || (decision != ApplicationStatus.APPROVED && decision != ApplicationStatus.REJECTED)) {
            throw new InvalidApplicationTransitionException("只有已提交的申请可以审核");
        }
        Instant now = Instant.now();
        return new AdoptionApplication(id, listingId, applicantUserId, decision, message, createdAt, now, null,
                reviewerUserId, now, reviewComment);
    }
}
