package org.animallink.incident.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.incident.application.AdminRescueReadService;
import org.animallink.incident.infrastructure.IncidentAdminAssertionFilter;
import org.animallink.incident.infrastructure.IncidentAdminAssertionVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/rescues")
public class AdminRescueReadController {
    private final AdminRescueReadService service;
    public AdminRescueReadController(AdminRescueReadService service) { this.service = service; }
    @GetMapping
    public AdminRescueDtos.Page search(HttpServletRequest request,
            @RequestParam(name = "campusId") String campusId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sort", defaultValue = "UPDATED_DESC") String sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        requireAdmin(request); return service.search(campusId, status, keyword, sort, page, size);
    }
    @GetMapping("/{eventId}")
    public AdminRescueDtos.RescueDetail detail(HttpServletRequest request,
            @PathVariable(name = "eventId") String eventId) {
        requireAdmin(request); return service.detail(eventId);
    }
    private static void requireAdmin(HttpServletRequest request) {
        if (!(request.getAttribute(IncidentAdminAssertionFilter.PRINCIPAL) instanceof IncidentAdminAssertionVerifier.AuthenticatedAdmin))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "trusted administrator required");
    }
}
