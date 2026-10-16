package org.animallink.identity.api;

import org.animallink.identity.application.UserGovernanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserGovernanceController {
    private final UserGovernanceService service;

    public AdminUserGovernanceController(UserGovernanceService service) {
        this.service = service;
    }

    @GetMapping("/{userId}")
    public UserGovernanceDtos.AdminUserResponse detail(
            @PathVariable("userId") String userId) {
        return UserGovernanceDtos.AdminUserResponse.from(service.detail(userId));
    }

    @GetMapping("/{userId}/governance-actions")
    public UserGovernanceDtos.GovernanceHistoryResponse history(
            @PathVariable("userId") String userId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return UserGovernanceDtos.GovernanceHistoryResponse.from(
                service.history(userId, page, size));
    }

    @PostMapping("/{userId}/restrict")
    public UserGovernanceDtos.AdminUserResponse restrict(
            @PathVariable("userId") String userId,
            @RequestBody UserGovernanceDtos.ReasonRequest request) {
        return UserGovernanceDtos.AdminUserResponse.from(
                service.restrict(userId, request.reason()));
    }

    @PostMapping("/{userId}/unrestrict")
    public UserGovernanceDtos.AdminUserResponse unrestrict(
            @PathVariable("userId") String userId,
            @RequestBody UserGovernanceDtos.ReasonRequest request) {
        return UserGovernanceDtos.AdminUserResponse.from(
                service.unrestrict(userId, request.reason()));
    }
}
