package org.animallink.incident.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class IncidentMiniappRequestPolicyTest {
    private static final String ID = "23000000-0000-4000-8000-000000000001";
    @Test void claimRequiresSeparateScopeAndExactPostPath() {
        String path = "/api/v1/internal/miniapp/rescues/" + ID + "/claim";
        org.junit.jupiter.api.Assertions.assertEquals("incident.case.claim", IncidentMiniappRequestPolicy.requiredScope("POST", path).orElseThrow());
        assertFalse(IncidentMiniappRequestPolicy.requiredScope("GET", path).isPresent());
        assertTrue(IncidentMiniappRequestPolicy.isProtected(path));
    }
    @Test void resultAndCloseAreScopedPostOnly() {
        String result="/api/v1/internal/miniapp/cases/"+ID+"/result", close="/api/v1/internal/miniapp/cases/"+ID+"/close";
        org.junit.jupiter.api.Assertions.assertEquals("incident.case-result.write",IncidentMiniappRequestPolicy.requiredScope("POST",result).orElseThrow());
        org.junit.jupiter.api.Assertions.assertEquals("incident.case-close.write",IncidentMiniappRequestPolicy.requiredScope("POST",close).orElseThrow());
        assertFalse(IncidentMiniappRequestPolicy.requiredScope("GET",result).isPresent());
    }
    @Test void actionMethodsRequireDistinctScopes() {
        String path="/api/v1/internal/miniapp/cases/"+ID+"/actions";
        org.junit.jupiter.api.Assertions.assertEquals("incident.case-action.read",IncidentMiniappRequestPolicy.requiredScope("GET",path).orElseThrow());
        org.junit.jupiter.api.Assertions.assertEquals("incident.case-action.write",IncidentMiniappRequestPolicy.requiredScope("POST",path).orElseThrow());
        assertFalse(IncidentMiniappRequestPolicy.requiredScope("DELETE",path).isPresent());
        assertTrue(IncidentMiniappRequestPolicy.isProtected(path));
    }
    @Test void protectsOnlyTheGatewayInternalDraftContract() {
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("POST", "/api/v1/internal/miniapp/rescue-drafts").isPresent());
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("GET", "/api/v1/internal/miniapp/rescue-drafts/" + ID).isPresent());
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("PATCH", "/api/v1/internal/miniapp/rescue-drafts/" + ID).isPresent());
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("POST", "/api/v1/internal/miniapp/rescue-drafts/" + ID + "/submit").isPresent());
        assertFalse(IncidentMiniappRequestPolicy.requiredScope("DELETE", "/api/v1/internal/miniapp/rescue-drafts/" + ID).isPresent());
        assertFalse(IncidentMiniappRequestPolicy.requiredScope("POST", "/api/v1/internal/miniapp/rescue-drafts/" + ID + "/submit/").isPresent());
    }
}
