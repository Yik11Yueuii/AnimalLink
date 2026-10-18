package org.animallink.animal.api;

import jakarta.validation.Valid;
import org.animallink.animal.application.AnimalAdoptionLifecycleService;
import org.animallink.animal.application.AnimalApplicationService;
import org.animallink.animal.application.AnimalMergeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/animals")
public class AdminAnimalController {
    private final AnimalApplicationService applicationService;
    private final AnimalMergeService mergeService;
    private final AnimalAdoptionLifecycleService adoptionLifecycleService;

    public AdminAnimalController(AnimalApplicationService applicationService,
                                 AnimalMergeService mergeService,
                                 AnimalAdoptionLifecycleService adoptionLifecycleService) {
        this.applicationService = applicationService;
        this.mergeService = mergeService;
        this.adoptionLifecycleService = adoptionLifecycleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnimalDtos.AnimalResponse create(
            @Valid @RequestBody AnimalDtos.CreateAnimalRequest request) {
        return AnimalDtos.AnimalResponse.from(applicationService.create(request.toCommand()));
    }

    @PatchMapping("/{animalId}")
    public AnimalDtos.AnimalResponse correct(
            @PathVariable("animalId") String animalId,
            @Valid @RequestBody AnimalDtos.CorrectAnimalRequest request) {
        return AnimalDtos.AnimalResponse.from(applicationService.correct(animalId, request.toCommand()));
    }

    @PostMapping("/{animalId}/archive")
    public AnimalDtos.AnimalResponse archive(@PathVariable("animalId") String animalId) {
        return AnimalDtos.AnimalResponse.from(applicationService.archive(animalId));
    }

    @PostMapping("/{animalId}/adoption/open")
    public AnimalDtos.AdoptionStatusTransitionResponse openAdoption(
            @PathVariable("animalId") String animalId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AnimalDtos.AdoptionStatusTransitionRequest request) {
        return AnimalDtos.AdoptionStatusTransitionResponse.from(
                adoptionLifecycleService.open(animalId, request.reason(), idempotencyKey));
    }

    @PostMapping("/{animalId}/adoption/close")
    public AnimalDtos.AdoptionStatusTransitionResponse closeAdoption(
            @PathVariable("animalId") String animalId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AnimalDtos.AdoptionStatusTransitionRequest request) {
        return AnimalDtos.AdoptionStatusTransitionResponse.from(
                adoptionLifecycleService.close(animalId, request.reason(), idempotencyKey));
    }

    @GetMapping("/{animalId}")
    public AnimalDtos.GovernanceAnimalResponse detail(
            @PathVariable("animalId") String animalId) {
        return AnimalDtos.GovernanceAnimalResponse.from(
                applicationService.detailAnyStatus(animalId));
    }

    @PostMapping("/{sourceAnimalId}/merge")
    public AnimalDtos.MergeResponse merge(
            @PathVariable("sourceAnimalId") String sourceAnimalId,
            @Valid @RequestBody AnimalDtos.MergeRequest request) {
        return AnimalDtos.MergeResponse.from(mergeService.merge(
                sourceAnimalId, request.targetAnimalId(), request.reason()));
    }

    @GetMapping("/{animalId}/merge-history")
    public AnimalDtos.PageResponse<AnimalDtos.MergeHistoryResponse> history(
            @PathVariable("animalId") String animalId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        var result = mergeService.history(animalId, page, size);
        return new AnimalDtos.PageResponse<>(result.items().stream()
                .map(AnimalDtos.MergeHistoryResponse::from).toList(),
                result.page(), result.size(), result.total());
    }
}
