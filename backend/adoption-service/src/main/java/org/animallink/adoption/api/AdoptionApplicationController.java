package org.animallink.adoption.api;

import jakarta.validation.Valid;
import org.animallink.adoption.application.AdoptionApplicationService;
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
public class AdoptionApplicationController {
    private final AdoptionApplicationService service;
    public AdoptionApplicationController(AdoptionApplicationService service) { this.service = service; }
    @PostMapping("/listings/{listingId}/applications") @ResponseStatus(HttpStatus.CREATED)
    public AdoptionApplicationDtos.ApplicationResponse submit(@PathVariable("listingId") String listingId, @Valid @RequestBody AdoptionApplicationDtos.SubmitApplicationRequest request) { return AdoptionApplicationDtos.ApplicationResponse.from(service.submit(listingId, request.message())); }
    @GetMapping("/applications/{applicationId}")
    public AdoptionApplicationDtos.ApplicationResponse detail(@PathVariable("applicationId") String applicationId) { return AdoptionApplicationDtos.ApplicationResponse.from(service.detail(applicationId)); }
    @GetMapping("/me/applications")
    public AdoptionApplicationDtos.PageResponse mine(@RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) { return AdoptionApplicationDtos.PageResponse.from(service.myApplications(page, size)); }
    @PostMapping("/applications/{applicationId}/withdraw")
    public AdoptionApplicationDtos.ApplicationResponse withdraw(@PathVariable("applicationId") String applicationId) { return AdoptionApplicationDtos.ApplicationResponse.from(service.withdraw(applicationId)); }
}
