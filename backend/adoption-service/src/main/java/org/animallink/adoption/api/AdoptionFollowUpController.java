package org.animallink.adoption.api;

import jakarta.validation.Valid;
import org.animallink.adoption.application.AdoptionFollowUpService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AdoptionFollowUpController {
    private final AdoptionFollowUpService service;
    public AdoptionFollowUpController(AdoptionFollowUpService service) { this.service = service; }
    @PostMapping("/me/relations/{relationId}/follow-ups") @ResponseStatus(HttpStatus.CREATED)
    public AdoptionFollowUpDtos.Response create(@PathVariable("relationId") String relationId, @Valid @RequestBody AdoptionFollowUpDtos.CreateRequest request) { return AdoptionFollowUpDtos.Response.from(service.create(relationId, request.content(), request.followedUpAt())); }
    @GetMapping("/me/relations/{relationId}/follow-ups")
    public AdoptionFollowUpDtos.PageResponse mine(@PathVariable("relationId") String relationId, @RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) { return AdoptionFollowUpDtos.PageResponse.from(service.mine(relationId, page, size)); }
    @GetMapping("/governance/relations/{relationId}/follow-ups")
    public AdoptionFollowUpDtos.PageResponse governance(@PathVariable("relationId") String relationId, @RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) { return AdoptionFollowUpDtos.PageResponse.from(service.governance(relationId, page, size)); }
}
