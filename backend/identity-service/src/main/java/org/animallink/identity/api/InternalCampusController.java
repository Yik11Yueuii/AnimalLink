package org.animallink.identity.api;

import org.animallink.identity.application.IdentityQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/campuses")
public class InternalCampusController {
    private final IdentityQueryService queryService;

    public InternalCampusController(IdentityQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{campusId}")
    public IdentityDtos.CampusResponse status(@PathVariable("campusId") String campusId) {
        return IdentityDtos.CampusResponse.from(queryService.getCampusForServiceValidation(campusId));
    }
}
