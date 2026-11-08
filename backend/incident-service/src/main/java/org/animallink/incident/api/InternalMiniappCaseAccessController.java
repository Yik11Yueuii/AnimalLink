package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionFilter;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionVerifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalMiniappCaseAccessController {
    private final JdbcTemplate jdbc; private final CaseActionController actions; private final HttpServletRequest request;
    public InternalMiniappCaseAccessController(JdbcTemplate jdbc, CaseActionController actions, HttpServletRequest request) { this.jdbc=jdbc; this.actions=actions; this.request=request; }
    @GetMapping("/api/v1/internal/miniapp/rescues/{eventId}/case")
    public CaseView get(@PathVariable("eventId") String event, @RequestParam("campusId") String campus) {
        Object principal=request.getAttribute(IncidentMiniappAssertionFilter.PRINCIPAL);
        if (!(principal instanceof IncidentMiniappAssertionVerifier.AuthenticatedMiniappUser user) || !"incident.case-action.read".equals(user.scope())) throw new EventController.Unauthorized("Trusted miniapp identity required");
        var ids=jdbc.queryForList("SELECT id FROM animal_case WHERE event_id=?",String.class,event);
        if (ids.isEmpty()) throw new EventController.NotFound("Case 不存在");
        var page=actions.listForTrustedMiniappUser(ids.getFirst(),user.userId(),campus,0,1);
        return new CaseView(page.caseId(),page.eventId(),page.campusId(),page.status());
    }
    record CaseView(String id,String eventId,String campusId,String status) {}
}
