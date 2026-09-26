package org.animallink.identity.api;

import org.animallink.identity.application.IdentityQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me")
public class CurrentUserController {
    private final IdentityQueryService queryService;

    public CurrentUserController(IdentityQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public IdentityDtos.UserResponse currentUser() {
        return IdentityDtos.UserResponse.from(queryService.currentUser());
    }

    @GetMapping("/campus-memberships")
    public List<IdentityDtos.CampusMembershipResponse> memberships() {
        return queryService.currentUserMemberships().stream()
                .map(IdentityDtos.CampusMembershipResponse::from)
                .toList();
    }
}
