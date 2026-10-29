package org.animallink.adoption.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.adoption.application.AdminAdoptionReadService;
import org.animallink.adoption.infrastructure.AdoptionAdminAssertionFilter;
import org.animallink.adoption.infrastructure.AdoptionAdminAssertionVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/applications")
public class AdminAdoptionReadController {
    private final AdminAdoptionReadService service;
    public AdminAdoptionReadController(AdminAdoptionReadService service) { this.service = service; }
    @GetMapping
    public AdminAdoptionDtos.Page search(HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sort", defaultValue = "UPDATED_DESC") String sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        requireAdmin(request); return service.search(status, keyword, sort, page, size);
    }
    @GetMapping("/{applicationId}")
    public AdminAdoptionDtos.Application detail(HttpServletRequest request,
            @PathVariable(name = "applicationId") String applicationId) {
        requireAdmin(request); return service.detail(applicationId);
    }
    private static void requireAdmin(HttpServletRequest request) {
        if (!(request.getAttribute(AdoptionAdminAssertionFilter.PRINCIPAL) instanceof AdoptionAdminAssertionVerifier.AuthenticatedAdmin))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "trusted administrator required");
    }
}
