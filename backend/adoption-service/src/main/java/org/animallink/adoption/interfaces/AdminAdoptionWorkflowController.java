package org.animallink.adoption.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.animallink.adoption.application.AdoptionApplicationService;
import org.animallink.adoption.application.AdoptionHandoverService;
import org.animallink.adoption.application.AdoptionSelectionService;
import org.animallink.adoption.application.AdoptionListingService;
import org.animallink.adoption.api.AdoptionListingDtos;
import org.animallink.adoption.application.AdminAdoptionReadService;
import org.animallink.adoption.infrastructure.AdoptionAdminAssertionFilter;
import org.animallink.adoption.infrastructure.AdoptionAdminAssertionVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAdoptionWorkflowController {
    private final AdoptionApplicationService applications;
    private final AdoptionSelectionService selections;
    private final AdoptionHandoverService handovers;
    private final AdminAdoptionReadService read;
    private final AdoptionListingService listings;

    public AdminAdoptionWorkflowController(AdoptionApplicationService applications, AdoptionSelectionService selections,
            AdoptionHandoverService handovers, AdminAdoptionReadService read, AdoptionListingService listings) {
        this.applications = applications; this.selections = selections; this.handovers = handovers; this.read = read; this.listings = listings;
    }
    @GetMapping("/listings")
    public AdoptionListingDtos.PageResponse listings(HttpServletRequest request,
            @RequestParam(name="status", required=false) String status,
            @RequestParam(name="page", defaultValue="0") int page,
            @RequestParam(name="size", defaultValue="20") int size) {
        admin(request); return AdoptionListingDtos.PageResponse.from(listings.governanceListings(status, page, size));
    }
    @GetMapping("/listings/{id}")
    public AdoptionListingDtos.ListingResponse listing(HttpServletRequest request, @PathVariable("id") String id) {
        admin(request); return AdoptionListingDtos.ListingResponse.from(listings.detail(id));
    }
    @PostMapping("/listings")
    public AdoptionListingDtos.ListingResponse createListing(HttpServletRequest request,
            @Valid @RequestBody AdoptionListingDtos.CreateListingRequest body) {
        admin(request); return AdoptionListingDtos.ListingResponse.from(listings.create(body.animalId(), body.title(), body.description()));
    }
    @PostMapping("/listings/{id}/publish")
    public AdoptionListingDtos.ListingResponse publishListing(HttpServletRequest request, @PathVariable("id") String id) {
        admin(request); return AdoptionListingDtos.ListingResponse.from(listings.publish(id));
    }
    @PostMapping("/applications/{id}/approve")
    public AdminAdoptionDtos.Application approve(HttpServletRequest request, @PathVariable("id") String id,
            @Valid @RequestBody Review body) {
        admin(request); applications.approve(id, body.comment()); return read.detail(id);
    }
    @PostMapping("/applications/{id}/reject")
    public AdminAdoptionDtos.Application reject(HttpServletRequest request, @PathVariable("id") String id,
            @Valid @RequestBody Rejection body) {
        admin(request); applications.reject(id, body.reason()); return read.detail(id);
    }
    @PostMapping("/listings/{id}/selection")
    public Selection select(HttpServletRequest request, @PathVariable("id") String id, @Valid @RequestBody Select body) {
        admin(request); return Selection.from(selections.select(id, body.applicationId(), body.note()).view());
    }
    @GetMapping("/listings/{id}/selection")
    public Selection selection(HttpServletRequest request, @PathVariable("id") String id) {
        admin(request); return Selection.from(selections.latestSelection(id));
    }
    @PostMapping("/selections/{id}/handover")
    public Handover initiate(HttpServletRequest request, @PathVariable("id") String id, @Valid @RequestBody Schedule body) {
        admin(request); return Handover.from(handovers.initiate(id, body.scheduledAt(), body.note()).view());
    }
    @GetMapping("/selections/{id}/handover")
    public Handover handover(HttpServletRequest request, @PathVariable("id") String id) {
        admin(request); return Handover.from(handovers.governance(id));
    }
    @PostMapping("/handovers/{id}/complete")
    public Handover complete(HttpServletRequest request, @PathVariable("id") String id) {
        admin(request); return Handover.from(handovers.complete(id).view());
    }
    @PostMapping("/handovers/{id}/cancel")
    public Handover cancel(HttpServletRequest request, @PathVariable("id") String id, @Valid @RequestBody Rejection body) {
        admin(request); return Handover.from(handovers.cancel(id, body.reason()).view());
    }
    private static void admin(HttpServletRequest request) {
        if (!(request.getAttribute(AdoptionAdminAssertionFilter.PRINCIPAL) instanceof AdoptionAdminAssertionVerifier.AuthenticatedAdmin))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "trusted administrator required");
    }
    public record Review(@Size(max=500) String comment) { }
    public record Rejection(@NotBlank @Size(max=500) String reason) { }
    public record Select(@NotBlank String applicationId, @Size(max=500) String note) { }
    public record Schedule(@NotNull OffsetDateTime scheduledAt, @Size(max=500) String note) { }
    public record Selection(String selectionId, String listingId, String applicationId, String status, Instant selectedAt) {
        static Selection from(org.animallink.adoption.application.SelectionView view) {
            var value = view.selection();
            return new Selection(value.id(), value.listingId(), value.applicationId(), value.status().name(), value.selectedAt());
        }
    }
    public record Handover(String handoverId, String selectionId, String status, Instant scheduledAt, Instant completedAt) {
        static Handover from(AdoptionHandoverService.View view) {
            var value = view.handover();
            return new Handover(value.id(), value.selectionId(), value.status().name(), value.scheduledAt(), value.completedAt());
        }
    }
}
