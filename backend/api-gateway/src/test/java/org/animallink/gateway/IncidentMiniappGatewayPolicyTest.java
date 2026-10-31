package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class IncidentMiniappGatewayPolicyTest {
    private static final String ID = "23000000-0000-4000-8000-000000000001";
    @Test void grantsOnlyTheFourExactMiniappDraftOperations() {
        assertTrue(IncidentMiniappGatewayPolicy.allowed("POST", "/auth/wechat/rescue-drafts"));
        assertTrue(IncidentMiniappGatewayPolicy.allowed("GET", "/auth/wechat/rescue-drafts/" + ID));
        assertTrue(IncidentMiniappGatewayPolicy.allowed("PATCH", "/auth/wechat/rescue-drafts/" + ID));
        assertTrue(IncidentMiniappGatewayPolicy.allowed("POST", "/auth/wechat/rescue-drafts/" + ID + "/submit"));
        assertFalse(IncidentMiniappGatewayPolicy.allowed("POST", "/auth/wechat/rescue-drafts/" + ID));
        assertFalse(IncidentMiniappGatewayPolicy.allowed("GET", "/auth/wechat/rescue-drafts/" + ID + "/submit"));
        assertFalse(IncidentMiniappGatewayPolicy.allowed("POST", "/auth/wechat/rescue-drafts/" + ID + "/submit/"));
        assertFalse(IncidentMiniappGatewayPolicy.allowed("POST", "/auth/wechat/rescue-drafts/not-a-uuid"));
    }
}
