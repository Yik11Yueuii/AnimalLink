package org.animallink.animal.domain;

import java.time.Instant;
import java.util.UUID;

public record ContentReport(
        String id,
        GovernanceResourceType resourceType,
        String resourceId,
        String reporterUserId,
        String description,
        ContentReportStatus status,
        ReportResolutionAction resolutionAction,
        String resolutionNote,
        String reviewedByUserId,
        Instant reviewedAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static ContentReport open(GovernanceResourceType resourceType, String resourceId,
                                     String reporterUserId, String description) {
        Instant now = Instant.now();
        return new ContentReport(UUID.randomUUID().toString(), resourceType, resourceId,
                reporterUserId, description, ContentReportStatus.OPEN, null, null, null, null,
                0, now, now);
    }

    public ContentReport dismiss(String reviewerUserId, String note) {
        requireOpen();
        Instant now = Instant.now();
        return new ContentReport(id, resourceType, resourceId, reporterUserId, description,
                ContentReportStatus.DISMISSED, null, note, reviewerUserId, now, version,
                createdAt, now);
    }

    public ContentReport resolve(ReportResolutionAction action, String reviewerUserId,
                                 String note) {
        requireOpen();
        Instant now = Instant.now();
        return new ContentReport(id, resourceType, resourceId, reporterUserId, description,
                ContentReportStatus.RESOLVED, action, note, reviewerUserId, now, version,
                createdAt, now);
    }

    private void requireOpen() {
        if (status != ContentReportStatus.OPEN) {
            throw new StateConflictException("举报已完成处置");
        }
    }
}
