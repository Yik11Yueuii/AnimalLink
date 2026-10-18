package org.animallink.adoption.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.animallink.adoption.application.AdoptionMergeEligibilityRepository;
import org.animallink.adoption.application.AdoptionMergeEligibilityService;
import org.animallink.adoption.application.InternalServiceAuthorization;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/adoption-merge-eligibility")
public class InternalAdoptionMergeEligibilityController {
    private final InternalServiceAuthorization internalAuthorization;
    private final AdoptionMergeEligibilityService eligibilityService;

    public InternalAdoptionMergeEligibilityController(InternalServiceAuthorization internalAuthorization,
                                                      AdoptionMergeEligibilityService eligibilityService) {
        this.internalAuthorization = internalAuthorization;
        this.eligibilityService = eligibilityService;
    }

    @PostMapping("/check")
    public Response check(
            @RequestHeader(name = "X-Internal-Service", required = false) String internalService,
            @Valid @RequestBody Request request) {
        internalAuthorization.requireAnimalService(internalService);
        AdoptionMergeEligibilityService.Result result = eligibilityService.check(
                request.sourceAnimalId(), request.targetAnimalId());
        return new Response(result.eligible(), result.blockers().stream().map(Blocker::from).toList());
    }

    @PostMapping("/check-animal")
    public Response checkAnimal(
            @RequestHeader(name = "X-Internal-Service", required = false) String internalService,
            @Valid @RequestBody AnimalRequest request) {
        internalAuthorization.requireAnimalService(internalService);
        AdoptionMergeEligibilityService.Result result = eligibilityService.checkAnimal(
                request.animalId());
        return new Response(result.eligible(), result.blockers().stream().map(Blocker::from).toList());
    }

    public record Request(@NotBlank String sourceAnimalId, @NotBlank String targetAnimalId) {
    }

    public record AnimalRequest(@NotBlank String animalId) {
    }

    public record Response(boolean eligible, List<Blocker> blockers) {
    }

    public record Blocker(String animalId, String blockerCode) {
        static Blocker from(AdoptionMergeEligibilityRepository.Blocker blocker) {
            return new Blocker(blocker.animalId(), blocker.blockerCode().name());
        }
    }
}
