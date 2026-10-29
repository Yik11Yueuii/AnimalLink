package org.animallink.incident.api;

import org.animallink.incident.application.PublicRescueReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous, privacy-minimized read model for verified rescue events. */
@RestController
@RequestMapping("/api/v1/public/rescues")
public class PublicRescueController {
    private final PublicRescueReadService service;

    public PublicRescueController(PublicRescueReadService service) {
        this.service = service;
    }

    @GetMapping
    public PublicRescueReadService.Page search(@RequestParam("campusId") String campusId,
                                                @RequestParam(value = "status", required = false) String status,
                                                @RequestParam(value = "page", defaultValue = "0") int page,
                                                @RequestParam(value = "size", defaultValue = "20") int size) {
        return service.search(campusId, status, page, size);
    }

    @GetMapping("/{eventId}")
    public PublicRescueReadService.Detail detail(@PathVariable("eventId") String eventId,
                                                  @RequestParam("campusId") String campusId) {
        return service.detail(campusId, eventId);
    }
}
