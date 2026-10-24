package org.animallink.animal.api;

import org.animallink.animal.application.AnimalQueryService;
import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalSpecies;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/animals")
public class PublicAnimalController {
    private final AnimalQueryService queryService;

    public PublicAnimalController(AnimalQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public AnimalDtos.PageResponse<AnimalDtos.AnonymousAnimalSummaryResponse> list(
            @RequestParam("campusId") String campusId,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "species", required = false) AnimalSpecies species,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<Animal> result = queryService.searchPublic(campusId, query, species, page, size);
        return new AnimalDtos.PageResponse<>(result.items().stream()
                .map(AnimalDtos.AnonymousAnimalSummaryResponse::from).toList(),
                result.page(), result.size(), result.total());
    }

    @GetMapping("/{animalId}")
    public AnimalDtos.AnonymousAnimalDetailResponse detail(
            @PathVariable("animalId") String animalId) {
        return AnimalDtos.AnonymousAnimalDetailResponse.from(
                queryService.publicProfile(animalId));
    }
}
