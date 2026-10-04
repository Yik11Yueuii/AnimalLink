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
    public record ApplicationResponse(String applicationId, String listingId, String applicantUserId, String status, String message, Instant createdAt, Instant updatedAt, Instant withdrawnAt) {
        public static ApplicationResponse from(AdoptionApplication value) { return new ApplicationResponse(value.id(), value.listingId(), value.applicantUserId(), value.status().name(), value.message(), value.createdAt(), value.updatedAt(), value.withdrawnAt()); }
    }
    public record PageResponse(List<ApplicationResponse> items, int page, int size, long total) {
        public static PageResponse from(PageResult<AdoptionApplication> page) { return new PageResponse(page.items().stream().map(ApplicationResponse::from).toList(), page.page(), page.size(), page.total()); }
    }
}
