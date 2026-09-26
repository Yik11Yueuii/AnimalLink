package org.animallink.animal.api;

import jakarta.validation.Valid;
import org.animallink.animal.application.AnimalApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/animals")
public class AdminAnimalController {
    private final AnimalApplicationService applicationService;

    public AdminAnimalController(AnimalApplicationService applicationService) {
        this.applicationService = applicationService;
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
}
