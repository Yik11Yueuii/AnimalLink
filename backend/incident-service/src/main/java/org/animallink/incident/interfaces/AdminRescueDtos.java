package org.animallink.incident.interfaces;

import java.time.Instant;
import java.util.List;

public final class AdminRescueDtos {
    private AdminRescueDtos() { }
    public record RescueItem(String eventId, String caseId, String campusId, String animalId,
                             String rescueStatus, String eventStatus, String caseStatus,
                             String claimStatus, boolean hasOwner, String abnormalitySummary,
                             String description, String publicLocationDescription,
                             Instant occurredAt, Instant reportedAt, long evidenceCount,
                             long actionCount, String resultCode, String resultSummary,
                             Instant updatedAt) { }
    public record Progress(String id, String description, Instant occurredAt,
                           Instant createdAt, String resultCode) { }
    public record RescueDetail(RescueItem rescue, Instant claimedAt, Instant resultSubmittedAt,
                               Instant closedAt, List<Progress> progress) { }
    public record Page(List<RescueItem> items, int page, int size, long total) { }
}
