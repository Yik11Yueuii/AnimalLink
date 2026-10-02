package org.animallink.identity.api;

import jakarta.validation.Valid;
import org.animallink.identity.application.VolunteerMembershipService;
import org.animallink.identity.domain.VolunteerMembershipStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/volunteer-memberships")
public class AdminVolunteerMembershipController {
    private final VolunteerMembershipService service;
    public AdminVolunteerMembershipController(VolunteerMembershipService service) { this.service = service; }
    @GetMapping
    public List<IdentityDtos.VolunteerMembershipResponse> list(
            @RequestParam(name = "campusId", required = false) String campusId,
            @RequestParam(name = "status", defaultValue = "PENDING_REVIEW") VolunteerMembershipStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return service.listForAdmin(campusId, status, page, size).stream().map(IdentityDtos.VolunteerMembershipResponse::from).toList();
    }
    @PostMapping("/{membershipId}/approve")
    public IdentityDtos.VolunteerMembershipResponse approve(@PathVariable("membershipId") String membershipId,
                                                             @Valid @RequestBody(required = false) IdentityDtos.ReviewRequest request) {
        return IdentityDtos.VolunteerMembershipResponse.from(service.approve(membershipId, request == null ? null : request.reviewReason()));
    }
    @PostMapping("/{membershipId}/reject")
    public IdentityDtos.VolunteerMembershipResponse reject(@PathVariable("membershipId") String membershipId,
                                                            @Valid @RequestBody IdentityDtos.RejectRequest request) {
        return IdentityDtos.VolunteerMembershipResponse.from(service.reject(membershipId, request.reviewReason()));
    }
    @PostMapping("/{membershipId}/revoke")
    public IdentityDtos.VolunteerMembershipResponse revoke(@PathVariable("membershipId") String membershipId,
                                                            @Valid @RequestBody IdentityDtos.RejectRequest request) {
        return IdentityDtos.VolunteerMembershipResponse.from(service.revoke(membershipId, request.reviewReason()));
    }
}
