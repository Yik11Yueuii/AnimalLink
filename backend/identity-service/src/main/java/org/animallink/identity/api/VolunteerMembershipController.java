package org.animallink.identity.api;

import jakarta.validation.Valid;
import org.animallink.identity.application.VolunteerMembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/volunteer-memberships")
public class VolunteerMembershipController {
    private final VolunteerMembershipService service;
    public VolunteerMembershipController(VolunteerMembershipService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityDtos.VolunteerMembershipResponse apply(@Valid @RequestBody IdentityDtos.VolunteerMembershipApplyRequest request) {
        return IdentityDtos.VolunteerMembershipResponse.from(service.apply(request.campusId(), request.applicationNote()));
    }
    @GetMapping
    public List<IdentityDtos.VolunteerMembershipResponse> mine(
            @RequestParam(name = "campusId", required = false) String campusId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return service.mine(campusId, page, size).stream().map(IdentityDtos.VolunteerMembershipResponse::from).toList();
    }
    @PostMapping("/{membershipId}/pause")
    public IdentityDtos.VolunteerMembershipResponse pause(@PathVariable("membershipId") String membershipId) { return IdentityDtos.VolunteerMembershipResponse.from(service.pause(membershipId)); }
    @PostMapping("/{membershipId}/resume")
    public IdentityDtos.VolunteerMembershipResponse resume(@PathVariable("membershipId") String membershipId) { return IdentityDtos.VolunteerMembershipResponse.from(service.resume(membershipId)); }
    @PostMapping("/{membershipId}/exit")
    public IdentityDtos.VolunteerMembershipResponse exit(@PathVariable("membershipId") String membershipId) { return IdentityDtos.VolunteerMembershipResponse.from(service.exit(membershipId)); }
}
