package org.animallink.identity.api;

import jakarta.validation.Valid;
import org.animallink.identity.application.CampusVerificationApplicationService;
import org.animallink.identity.domain.VerificationStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/campus-verifications")
public class AdminCampusVerificationController {
    private final CampusVerificationApplicationService service;

    public AdminCampusVerificationController(CampusVerificationApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<IdentityDtos.CampusVerificationResponse> list(
            @RequestParam(name = "status", defaultValue = "PENDING_REVIEW") VerificationStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return service.listForAdmin(status, page, size).stream()
                .map(IdentityDtos.CampusVerificationResponse::from).toList();
    }

    @GetMapping("/{verificationId}")
    public IdentityDtos.CampusVerificationResponse detail(
            @PathVariable("verificationId") String verificationId) {
        return IdentityDtos.CampusVerificationResponse.from(service.getForAdmin(verificationId));
    }

    @PostMapping("/{verificationId}/approve")
    public IdentityDtos.CampusVerificationResponse approve(
            @PathVariable("verificationId") String verificationId,
            @Valid @RequestBody(required = false) IdentityDtos.ReviewRequest request) {
        String reason = request == null ? null : request.reviewReason();
        return IdentityDtos.CampusVerificationResponse.from(service.approve(verificationId, reason));
    }

    @PostMapping("/{verificationId}/reject")
    public IdentityDtos.CampusVerificationResponse reject(
            @PathVariable("verificationId") String verificationId,
            @Valid @RequestBody IdentityDtos.RejectRequest request) {
        return IdentityDtos.CampusVerificationResponse.from(
                service.reject(verificationId, request.reviewReason()));
    }
}
