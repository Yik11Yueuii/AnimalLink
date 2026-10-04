package org.animallink.adoption.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.animallink.adoption.application.PageResult;
import org.animallink.adoption.domain.AdoptionListing;

public final class AdoptionListingDtos {
    private AdoptionListingDtos() { }
    public record CreateListingRequest(@NotBlank @Size(max = 36) String animalId,
                                      @NotBlank @Size(max = 160) String title,
                                      @NotBlank @Size(max = 4000) String description) { }
    public record ListingResponse(String listingId, String animalId, String status, String title, String description,
                                  String publisherUserId, Instant createdAt, Instant updatedAt, Instant publishedAt, Instant closedAt) {
        public static ListingResponse from(AdoptionListing value) { return new ListingResponse(value.id(), value.animalId(), value.status().name(), value.title(), value.description(), value.publisherUserId(), value.createdAt(), value.updatedAt(), value.publishedAt(), value.closedAt()); }
    }
    public record PageResponse(List<ListingResponse> items, int page, int size, long total) {
        public static PageResponse from(PageResult<AdoptionListing> page) { return new PageResponse(page.items().stream().map(ListingResponse::from).toList(), page.page(), page.size(), page.total()); }
    }
}
