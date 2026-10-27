package org.animallink.identity.api;

import org.animallink.identity.application.UniversityQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/universities")
public class UniversityController {
    private final UniversityQueryService queryService;

    public UniversityController(UniversityQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public IdentityDtos.PageResponse<IdentityDtos.UniversityResponse> list(
            @RequestParam(name = "province", defaultValue = "") String province,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        var result = queryService.search(province, q, page, size);
        return new IdentityDtos.PageResponse<>(
                result.items().stream().map(IdentityDtos.UniversityResponse::from).toList(),
                result.page(), result.size(), result.total());
    }

    @GetMapping("/{universityId}/community")
    public IdentityDtos.CampusResponse community(
            @PathVariable("universityId") String universityId) {
        return IdentityDtos.CampusResponse.from(queryService.activeCommunity(universityId));
    }
}
