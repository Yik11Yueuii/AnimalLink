package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionFilter;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionVerifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal gateway-only adapter. It never reads user identity from client headers or request bodies. */
@RestController
@RequestMapping("/api/v1/internal/miniapp/rescue-drafts")
public class InternalMiniappRescueDraftController {
    private final EventController events; private final HttpServletRequest request;
    public InternalMiniappRescueDraftController(EventController events, HttpServletRequest request) { this.events = events; this.request = request; }
    @PostMapping public EventController.DraftResponse create(@Valid @RequestBody EventController.DraftRequest body) { return events.createDraftForTrustedMiniappUser(actor(), minimal(body)); }
    @GetMapping("/{draftId}") public EventController.DraftResponse get(@PathVariable("draftId") String draftId) { return events.draftForTrustedMiniappUser(actor(), draftId); }
    @PatchMapping("/{draftId}") public EventController.DraftResponse patch(@PathVariable("draftId") String draftId, @Valid @RequestBody EventController.DraftRequest body) { return events.patchDraftForTrustedMiniappUser(actor(), draftId, minimal(body)); }
    @PostMapping("/{draftId}/submit") public EventController.EventResponse submit(@PathVariable("draftId") String draftId) { return events.submitForTrustedMiniappUser(actor(), draftId); }
    private String actor() {
        Object principal = request.getAttribute(IncidentMiniappAssertionFilter.PRINCIPAL);
        if (!(principal instanceof IncidentMiniappAssertionVerifier.AuthenticatedMiniappUser user)) throw new EventController.Unauthorized("需要可信普通用户身份");
        return user.userId();
    }
    private static EventController.DraftRequest minimal(EventController.DraftRequest value) {
        if ((value.media() != null && !value.media().isEmpty()) || present(value.sourceAiTaskId()) || present(value.sourceMatchingRecordId()) || present(value.sourcePostId()))
            throw new IllegalArgumentException("本阶段救助上报不接受媒体或 AI 来源");
        return new EventController.DraftRequest(value.campusId(), value.animalId(), value.description(), value.abnormalitySummary(), value.occurredAt(), value.publicLocationDescription(), value.exactLocationDescription(), value.latitude(), value.longitude(), null, null, null, List.of());
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
