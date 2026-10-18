package org.animallink.adoption.api;

import org.animallink.adoption.application.AdoptionLifecycleAuthorityService;
import org.animallink.adoption.application.InternalServiceAuthorization;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/adoption-lifecycle")
public class InternalAdoptionLifecycleController {
    private final InternalServiceAuthorization authorization;
    private final AdoptionLifecycleAuthorityService service;

    public InternalAdoptionLifecycleController(InternalServiceAuthorization authorization,
                                               AdoptionLifecycleAuthorityService service) {
        this.authorization = authorization;
        this.service = service;
    }

    @GetMapping("/animals/{animalId}")
    public AdoptionLifecycleAuthorityService.Result latest(
            @RequestHeader(name = "X-Internal-Service", required = false) String caller,
            @PathVariable("animalId") String animalId) {
        authorization.requireAnimalService(caller);
        return service.latest(animalId);
    }
}
