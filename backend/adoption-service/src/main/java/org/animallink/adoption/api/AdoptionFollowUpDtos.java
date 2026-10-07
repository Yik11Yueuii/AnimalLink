package org.animallink.adoption.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.animallink.adoption.application.PageResult;
import org.animallink.adoption.domain.AdoptionFollowUp;

public final class AdoptionFollowUpDtos {
    private AdoptionFollowUpDtos() { }
    public record CreateRequest(@NotBlank @Size(max = 2000) String content, @NotNull OffsetDateTime followedUpAt) { }
    public record Response(String id, String relationId, String content, Instant followedUpAt, Instant createdAt, Instant updatedAt) {
        public static Response from(AdoptionFollowUp value) { return new Response(value.id(), value.relationId(), value.content(), value.followedUpAt(), value.createdAt(), value.updatedAt()); }
    }
    public record PageResponse(List<Response> items, int page, int size, long total) {
        public static PageResponse from(PageResult<AdoptionFollowUp> page) { return new PageResponse(page.items().stream().map(Response::from).toList(), page.page(), page.size(), page.total()); }
    }
}
