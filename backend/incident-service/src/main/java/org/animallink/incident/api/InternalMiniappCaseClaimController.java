package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionFilter;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionVerifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** Only a Gateway-verified session can provide the actor to the existing claim transaction. */
@RestController
public class InternalMiniappCaseClaimController {
    private final CaseController cases;
    private final HttpServletRequest request;

    public InternalMiniappCaseClaimController(CaseController cases, HttpServletRequest request) {
        this.cases = cases;
        this.request = request;
    }

    @PostMapping("/api/v1/internal/miniapp/rescues/{eventId}/claim")
    public ClaimResponse claim(@PathVariable("eventId") String eventId, @Valid @RequestBody ClaimRequest body) {
        Object principal = request.getAttribute(IncidentMiniappAssertionFilter.PRINCIPAL);
        if (!(principal instanceof IncidentMiniappAssertionVerifier.AuthenticatedMiniappUser user)
                || !"incident.case.claim".equals(user.scope())) {
            throw new EventController.Unauthorized("Trusted miniapp identity required");
        }
        var result = cases.claimForTrustedMiniappUser(eventId, user.userId(), body.campusId());
        return new ClaimResponse(result.id(), result.eventId(), result.campusId(), result.status(), result.claimStatus(), result.claimedAt());
    }
    public record ClaimRequest(@NotBlank String campusId) {}
    public record ClaimResponse(String id, String eventId, String campusId, String status, String claimStatus, java.time.Instant claimedAt) {}
}
