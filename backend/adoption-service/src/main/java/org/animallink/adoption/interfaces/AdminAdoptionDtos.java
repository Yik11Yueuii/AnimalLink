package org.animallink.adoption.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AdminAdoptionDtos {
    private AdminAdoptionDtos() { }
    public record Listing(String listingId, String animalId, String status, String title,
                          String description, Instant createdAt, Instant updatedAt,
                          Instant publishedAt, Instant closedAt) { }
    public record Application(String applicationId, String listingId, String applicantReference,
                              String status, String message, Instant createdAt, Instant updatedAt,
                              String reviewerReference, Instant reviewedAt, String reviewComment,
                              Listing listing) { }
    public record Page(List<Application> items, int page, int size, long total,
                       Map<String, Long> statusCounts) { }
}
