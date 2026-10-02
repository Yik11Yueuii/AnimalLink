package org.animallink.identity.api;

import org.animallink.identity.application.InternalServiceAuthorization;
import org.animallink.identity.application.VolunteerMembershipService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/users")
public class InternalVolunteerMembershipController {
    private final VolunteerMembershipService service;
    private final InternalServiceAuthorization authorization;
    public InternalVolunteerMembershipController(VolunteerMembershipService service, InternalServiceAuthorization authorization) { this.service = service; this.authorization = authorization; }
    @GetMapping("/{userId}/campuses/{campusId}/volunteer-membership")
    public IdentityDtos.InternalVolunteerMembershipResponse fact(
            @RequestHeader(name = "X-Internal-Service", required = false) String caller,
            @PathVariable("userId") String userId, @PathVariable("campusId") String campusId) {
        authorization.requireIncidentService(caller);
        return service.fact(userId, campusId).map(IdentityDtos.InternalVolunteerMembershipResponse::from)
                .orElseGet(() -> IdentityDtos.InternalVolunteerMembershipResponse.missing(userId, campusId));
    }
}
