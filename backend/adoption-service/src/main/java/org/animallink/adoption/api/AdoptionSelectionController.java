package org.animallink.adoption.api;

import jakarta.validation.Valid;
import org.animallink.adoption.application.AdoptionSelectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/listings")
public class AdoptionSelectionController {
    private final AdoptionSelectionService service;
    public AdoptionSelectionController(AdoptionSelectionService service) { this.service = service; }
    @PostMapping("/{listingId}/selection")
    public ResponseEntity<AdoptionSelectionDtos.SelectionResponse> select(@PathVariable(name = "listingId") String listingId,
            @Valid @RequestBody AdoptionSelectionDtos.SelectApplicationRequest request) {
        var result = service.select(listingId, request.applicationId(), request.note());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(AdoptionSelectionDtos.SelectionResponse.from(result.view()));
    }
    @GetMapping("/{listingId}/selection")
    public AdoptionSelectionDtos.SelectionResponse active(@PathVariable(name = "listingId") String listingId) {
        return AdoptionSelectionDtos.SelectionResponse.from(service.activeSelection(listingId));
    }
}
