package org.animallink.adoption.api;

import jakarta.validation.Valid;
import org.animallink.adoption.application.AdoptionListingService;
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
@RequestMapping("/api/v1/listings")
public class AdoptionListingController {
    private final AdoptionListingService service;
    public AdoptionListingController(AdoptionListingService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public AdoptionListingDtos.ListingResponse create(@Valid @RequestBody AdoptionListingDtos.CreateListingRequest request) {
        return AdoptionListingDtos.ListingResponse.from(service.create(request.animalId(), request.title(), request.description()));
    }
    @GetMapping("/{listingId}")
    public AdoptionListingDtos.ListingResponse detail(@PathVariable("listingId") String listingId) { return AdoptionListingDtos.ListingResponse.from(service.detail(listingId)); }
    @GetMapping
    public AdoptionListingDtos.PageResponse list(@RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) { return AdoptionListingDtos.PageResponse.from(service.publicListings(page, size)); }
    @PostMapping("/{listingId}/publish")
    public AdoptionListingDtos.ListingResponse publish(@PathVariable("listingId") String listingId) { return AdoptionListingDtos.ListingResponse.from(service.publish(listingId)); }
    @PostMapping("/{listingId}/close")
    public AdoptionListingDtos.ListingResponse close(@PathVariable("listingId") String listingId) { return AdoptionListingDtos.ListingResponse.from(service.close(listingId)); }
}
