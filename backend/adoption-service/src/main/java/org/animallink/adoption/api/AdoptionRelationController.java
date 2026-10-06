package org.animallink.adoption.api;

import jakarta.validation.Valid;
import org.animallink.adoption.application.AdoptionRelationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/relations")
public class AdoptionRelationController {
    private final AdoptionRelationService service;

    public AdoptionRelationController(AdoptionRelationService service) { this.service = service; }

    @PostMapping("/{relationId}/end")
    public ResponseEntity<AdoptionRelationDtos.GovernanceResponse> end(@PathVariable("relationId") String relationId, @Valid @RequestBody AdoptionRelationDtos.EndRelationRequest request) {
        return ResponseEntity.ok(AdoptionRelationDtos.GovernanceResponse.from(service.end(relationId, request.reason())));
    }
}
