package org.animallink.identity.api;

import jakarta.validation.Valid;
import org.animallink.identity.application.CampusVerificationApplicationService;
import org.animallink.identity.application.SubmitVerificationCommand;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/campus-verifications")
public class CampusVerificationController {
    private final CampusVerificationApplicationService service;

    public CampusVerificationController(CampusVerificationApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityDtos.CampusVerificationResponse submit(
            @Valid @RequestBody IdentityDtos.SubmitVerificationRequest request) {
        var command = new SubmitVerificationCommand(
                request.campusId(), request.requestedMembershipType(), request.applicantName(),
                request.affiliationNote(), request.expectedGraduationDate(), request.materialMediaId());
        return IdentityDtos.CampusVerificationResponse.from(service.submit(command));
    }

    @GetMapping
    public List<IdentityDtos.CampusVerificationResponse> listMine() {
        return service.listMine().stream().map(IdentityDtos.CampusVerificationResponse::from).toList();
    }

    @GetMapping("/{verificationId}")
    public IdentityDtos.CampusVerificationResponse detail(
            @PathVariable("verificationId") String verificationId) {
        return IdentityDtos.CampusVerificationResponse.from(service.getMine(verificationId));
    }
}
