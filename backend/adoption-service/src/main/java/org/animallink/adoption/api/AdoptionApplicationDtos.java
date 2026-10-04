package org.animallink.adoption.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.animallink.adoption.application.PageResult;
import org.animallink.adoption.domain.AdoptionApplication;

public final class AdoptionApplicationDtos {
    private AdoptionApplicationDtos() { }
    public record SubmitApplicationRequest(@NotBlank @Size(max = 2000) String message) { }
    public record ApplicationResponse(String applicationId, String listingId, String applicantUserId, String status, String message, Instant createdAt, Instant updatedAt, Instant withdrawnAt, Instant reviewedAt, String reviewComment) {
        public static ApplicationResponse from(AdoptionApplication value) { return new ApplicationResponse(value.id(), value.listingId(), value.applicantUserId(), value.status().name(), value.message(), value.createdAt(), value.updatedAt(), value.withdrawnAt(), value.reviewedAt(), value.reviewComment()); }
    }
    public record PageResponse(List<ApplicationResponse> items, int page, int size, long total) {
        public static PageResponse from(PageResult<AdoptionApplication> page) { return new PageResponse(page.items().stream().map(ApplicationResponse::from).toList(), page.page(), page.size(), page.total()); }
    }
    public record ReviewApplicationRequest(@Size(max = 500) String comment) { }
    public record RejectApplicationRequest(@Size(max = 500) String reason) { }
    public record GovernanceApplicationResponse(String applicationId, String listingId, String applicantUserId, String status, String message, Instant createdAt, Instant updatedAt, String reviewerUserId, Instant reviewedAt, String reviewComment) {
        public static GovernanceApplicationResponse from(AdoptionApplication value) { return new GovernanceApplicationResponse(value.id(), value.listingId(), value.applicantUserId(), value.status().name(), value.message(), value.createdAt(), value.updatedAt(), value.reviewerUserId(), value.reviewedAt(), value.reviewComment()); }
    }
    public record GovernancePageResponse(List<GovernanceApplicationResponse> items, int page, int size, long total) {
        public static GovernancePageResponse from(PageResult<AdoptionApplication> page) { return new GovernancePageResponse(page.items().stream().map(GovernanceApplicationResponse::from).toList(), page.page(), page.size(), page.total()); }
    }
}
