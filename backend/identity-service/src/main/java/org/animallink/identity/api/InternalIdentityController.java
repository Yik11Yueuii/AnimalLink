package org.animallink.identity.api;

import jakarta.validation.Valid;
import org.animallink.identity.application.IdentityQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/v1/users")
public class InternalIdentityController {
    private final IdentityQueryService queryService;

    public InternalIdentityController(IdentityQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{userId}/campus-memberships/{campusId}")
    public IdentityDtos.InternalMembershipResponse membership(
            @PathVariable("userId") String userId,
            @PathVariable("campusId") String campusId) {
        return queryService.membershipFact(userId, campusId)
                .map(IdentityDtos.InternalMembershipResponse::from)
                .orElseGet(IdentityDtos.InternalMembershipResponse::missing);
    }

    @PostMapping("/summaries")
    public List<IdentityDtos.UserSummaryResponse> summaries(
            @Valid @RequestBody IdentityDtos.UserSummariesRequest request) {
        return queryService.userSummaries(request.userIds()).stream()
                .map(IdentityDtos.UserSummaryResponse::from)
                .toList();
    }
}
