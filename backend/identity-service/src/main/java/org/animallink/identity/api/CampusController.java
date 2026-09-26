package org.animallink.identity.api;

import org.animallink.identity.application.IdentityQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/campuses")
public class CampusController {
    private final IdentityQueryService queryService;

    public CampusController(IdentityQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<IdentityDtos.CampusResponse> list(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return queryService.searchCampuses(q, page, size).stream()
                .map(IdentityDtos.CampusResponse::from)
                .toList();
    }

    @GetMapping("/{campusId}")
    public IdentityDtos.CampusResponse detail(@PathVariable("campusId") String campusId) {
        return IdentityDtos.CampusResponse.from(queryService.getCampus(campusId));
    }
}
