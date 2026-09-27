package org.animallink.animal.api;

import jakarta.validation.Valid;
import org.animallink.animal.application.AnimalIdentityProposalService;
import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.AnimalIdentityProposal;
import org.animallink.animal.domain.AnimalIdentityProposalStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/animal-identity-proposals")
public class AdminAnimalIdentityProposalController {
    private final AnimalIdentityProposalService service;

    public AdminAnimalIdentityProposalController(AnimalIdentityProposalService service) {
        this.service = service;
    }

    @GetMapping
    ObservationFinalizationDtos.PageResponse<ObservationFinalizationDtos.ProposalResponse> list(
            @RequestParam(name = "status", required = false)
            AnimalIdentityProposalStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<AnimalIdentityProposal> result = service.list(status, page, size);
        return new ObservationFinalizationDtos.PageResponse<>(
                result.items().stream()
                        .map(ObservationFinalizationDtos.ProposalResponse::summary).toList(),
                result.page(), result.size(), result.total());
    }

    @GetMapping("/{proposalId}")
    ObservationFinalizationDtos.ProposalResponse detail(
            @PathVariable("proposalId") String proposalId) {
        return ObservationFinalizationDtos.ProposalResponse.from(service.detail(proposalId));
    }

    @PostMapping("/{proposalId}/approve-create")
    ObservationFinalizationDtos.ProposalResponse approveCreate(
            @PathVariable("proposalId") String proposalId,
            @Valid @RequestBody ObservationFinalizationDtos.ApproveCreateRequest request) {
        return ObservationFinalizationDtos.ProposalResponse.from(
                service.approveCreate(proposalId, request.toCommand()));
    }

    @PostMapping("/{proposalId}/link-existing")
    ObservationFinalizationDtos.ProposalResponse linkExisting(
            @PathVariable("proposalId") String proposalId,
            @Valid @RequestBody ObservationFinalizationDtos.LinkExistingRequest request) {
        return ObservationFinalizationDtos.ProposalResponse.from(
                service.linkExisting(proposalId, request.animalId(), request.reviewReason()));
    }

    @PostMapping("/{proposalId}/reject")
    ObservationFinalizationDtos.ProposalResponse reject(
            @PathVariable("proposalId") String proposalId,
            @Valid @RequestBody ObservationFinalizationDtos.RejectRequest request) {
        return ObservationFinalizationDtos.ProposalResponse.from(
                service.reject(proposalId, request.reviewReason()));
    }
}
