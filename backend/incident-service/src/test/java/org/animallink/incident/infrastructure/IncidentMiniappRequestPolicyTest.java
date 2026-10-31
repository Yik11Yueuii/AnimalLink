package org.animallink.incident.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class IncidentMiniappRequestPolicyTest {
    private static final String ID = "23000000-0000-4000-8000-000000000001";
    @Test void protectsOnlyTheGatewayInternalDraftContract() {
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("POST", "/api/v1/internal/miniapp/rescue-drafts").isPresent());
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("GET", "/api/v1/internal/miniapp/rescue-drafts/" + ID).isPresent());
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("PATCH", "/api/v1/internal/miniapp/rescue-drafts/" + ID).isPresent());
        assertTrue(IncidentMiniappRequestPolicy.requiredScope("POST", "/api/v1/internal/miniapp/rescue-drafts/" + ID + "/submit").isPresent());
        assertFalse(IncidentMiniappRequestPolicy.requiredScope("DELETE", "/api/v1/internal/miniapp/rescue-drafts/" + ID).isPresent());
        assertFalse(IncidentMiniappRequestPolicy.requiredScope("POST", "/api/v1/internal/miniapp/rescue-drafts/" + ID + "/submit/").isPresent());
    }
}
