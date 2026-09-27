package org.animallink.animal.domain;

import java.time.Instant;

public record AnimalIdentityProposal(
        String id,
        String campusId,
        String createdByUserId,
        String sourceAiTaskId,
        String sourceMatchingRecordId,
        String postId,
        String proposedSpecies,
        String proposedSex,
        String proposedCoatColor,
        String proposedDistinctiveFeatures,
        String proposedDescription,
        AnimalIdentityProposalStatus status,
        Instant reviewedAt,
        String reviewedBy,
        String resolutionAnimalId,
        String reviewReason,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public AnimalIdentityProposal approve(String reviewerId, String animalId) {
        requirePending();
        Instant now = Instant.now();
        return reviewed(AnimalIdentityProposalStatus.APPROVED, reviewerId, animalId, null, now);
    }

    public AnimalIdentityProposal linkExisting(String reviewerId, String animalId, String reason) {
        requirePending();
        Instant now = Instant.now();
        return reviewed(AnimalIdentityProposalStatus.LINKED_EXISTING, reviewerId, animalId,
                optional(reason), now);
    }

    public AnimalIdentityProposal reject(String reviewerId, String reason) {
        requirePending();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reviewReason 不能为空");
        }
        Instant now = Instant.now();
        return reviewed(AnimalIdentityProposalStatus.REJECTED, reviewerId, null,
                reason.trim(), now);
    }

    private AnimalIdentityProposal reviewed(AnimalIdentityProposalStatus newStatus,
                                             String reviewerId, String animalId,
                                             String reason, Instant now) {
        return new AnimalIdentityProposal(id, campusId, createdByUserId, sourceAiTaskId,
                sourceMatchingRecordId, postId, proposedSpecies, proposedSex, proposedCoatColor,
                proposedDistinctiveFeatures, proposedDescription, newStatus, now, reviewerId,
                animalId, reason, version, createdAt, now);
    }

    private void requirePending() {
        if (status != AnimalIdentityProposalStatus.PENDING_REVIEW) {
            throw new StateConflictException("Proposal 已完成审核");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
