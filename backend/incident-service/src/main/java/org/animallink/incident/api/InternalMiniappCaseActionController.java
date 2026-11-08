package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionFilter;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionVerifier;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/miniapp/cases/{caseId}/actions")
public class InternalMiniappCaseActionController {
    private final CaseActionController actions;
    private final HttpServletRequest request;
    public InternalMiniappCaseActionController(CaseActionController actions, HttpServletRequest request) { this.actions = actions; this.request = request; }
    @GetMapping public CaseActionController.TrustedPage list(@PathVariable("caseId") String caseId, @RequestParam("campusId") String campusId, @RequestParam(name="page",defaultValue="0") int page, @RequestParam(name="size",defaultValue="20") int size) {
        return actions.listForTrustedMiniappUser(caseId, actor("incident.case-action.read"), campusId, page, size);
    }
    @PostMapping public CaseActionController.Response create(@PathVariable("caseId") String caseId, @Valid @RequestBody Input body) {
        return actions.createForTrustedMiniappUser(caseId, new CaseActionController.Request(body.description(), body.occurredAt(), List.of()), actor("incident.case-action.write"), body.campusId());
    }
    private String actor(String scope) {
        Object value = request.getAttribute(IncidentMiniappAssertionFilter.PRINCIPAL);
        if (!(value instanceof IncidentMiniappAssertionVerifier.AuthenticatedMiniappUser user) || !scope.equals(user.scope())) throw new EventController.Unauthorized("Trusted miniapp identity required");
        return user.userId();
    }
    public record Input(@NotBlank String campusId, @NotBlank @Size(max=2000) String description, @NotNull Instant occurredAt) {}
}
