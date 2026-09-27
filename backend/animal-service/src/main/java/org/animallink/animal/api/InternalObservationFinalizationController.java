package org.animallink.animal.api;

import jakarta.validation.Valid;
import org.animallink.animal.application.InternalServiceAuthorization;
import org.animallink.animal.application.ObservationFinalizationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/observation-finalizations")
public class InternalObservationFinalizationController {
    private final ObservationFinalizationService service;
    private final InternalServiceAuthorization authorization;

    public InternalObservationFinalizationController(
            ObservationFinalizationService service,
            InternalServiceAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ObservationFinalizationDtos.InternalFinalizeResponse finalizeObservation(
            @RequestHeader(name = "X-Internal-Service", required = false) String caller,
            @Valid @RequestBody ObservationFinalizationDtos.InternalFinalizeRequest request) {
        authorization.requireIntelligenceService(caller);
        return ObservationFinalizationDtos.InternalFinalizeResponse.from(
                service.finalizeObservation(request.toCommand()));
    }
}
