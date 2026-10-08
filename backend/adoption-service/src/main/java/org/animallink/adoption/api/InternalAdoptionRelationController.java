package org.animallink.adoption.api;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.adoption.application.AdoptionRelationAuthorizationService;
import org.animallink.adoption.application.InternalServiceAuthorization;
import org.animallink.adoption.domain.AuthenticationRequiredException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/adoption-relations")
public class InternalAdoptionRelationController {
    private final InternalServiceAuthorization internalAuthorization;
    private final AdoptionRelationAuthorizationService relationAuthorization;

    public InternalAdoptionRelationController(InternalServiceAuthorization internalAuthorization,
                                              AdoptionRelationAuthorizationService relationAuthorization) {
        this.internalAuthorization = internalAuthorization;
        this.relationAuthorization = relationAuthorization;
    }

    @GetMapping("/active")
    public ActiveRelationResponse active(
            @RequestHeader(name = "X-Internal-Service", required = false) String internalService,
            @RequestParam("animalId") String animalId,
            HttpServletRequest request) {
        internalAuthorization.requireAnimalService(internalService);
        String callerUserId = request.getHeader("X-User-Id");
        if (callerUserId == null || callerUserId.isBlank()) {
            throw new AuthenticationRequiredException("需要调用者身份");
        }
        return new ActiveRelationResponse(relationAuthorization.requireActiveRelation(animalId));
    }

    public record ActiveRelationResponse(String relationId) {
    }
}
