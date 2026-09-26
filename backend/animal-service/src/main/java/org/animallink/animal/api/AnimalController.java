package org.animallink.animal.api;

import org.animallink.animal.application.AnimalQueryService;
import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.TimelineEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/animals")
public class AnimalController {
    private final AnimalQueryService queryService;

    public AnimalController(AnimalQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public AnimalDtos.PageResponse<AnimalDtos.AnimalSummaryResponse> list(
            @RequestParam("campusId") String campusId,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "species", required = false) AnimalSpecies species,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<Animal> result = queryService.search(campusId, query, species, page, size);
        return new AnimalDtos.PageResponse<>(result.items().stream()
                .map(AnimalDtos.AnimalSummaryResponse::from).toList(),
                result.page(), result.size(), result.total());
    }

    @GetMapping("/{animalId}")
    public AnimalDtos.AnimalResponse detail(@PathVariable("animalId") String animalId) {
        return AnimalDtos.AnimalResponse.from(queryService.detail(animalId));
    }

    @GetMapping("/{animalId}/timeline")
    public AnimalDtos.PageResponse<AnimalDtos.TimelineEntryResponse> timeline(
            @PathVariable("animalId") String animalId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<TimelineEntry> result = queryService.timeline(animalId, page, size);
        return new AnimalDtos.PageResponse<>(result.items().stream()
                .map(AnimalDtos.TimelineEntryResponse::from).toList(),
                result.page(), result.size(), result.total());
    }
}
