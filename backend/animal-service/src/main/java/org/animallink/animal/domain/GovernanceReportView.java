package org.animallink.animal.domain;

public record GovernanceReportView(
        ContentReport report,
        String authorUserId,
        String campusId,
        String currentContentStatus,
        String contentText,
        PostStatus parentPostStatus) {
}
