package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class IncidentMiniappGatewayPolicyTest {
    private static final String ID = "23000000-0000-4000-8000-000000000001";
    @Test void claimUsesItsOwnScopeAndExactPostPath() {
        String path = "/auth/wechat/rescues/" + ID + "/claim";
        assertTrue(IncidentMiniappGatewayPolicy.allowed("POST", path));
        assertFalse(IncidentMiniappGatewayPolicy.allowed("GET", path));
        assertFalse(IncidentMiniappGatewayPolicy.allowed("POST", path + "/"));
        org.junit.jupiter.api.Assertions.assertEquals("incident.case.claim", IncidentMiniappGatewayPolicy.scope("POST", path));
        org.junit.jupiter.api.Assertions.assertEquals("/api/v1/internal/miniapp/rescues/" + ID + "/claim", IncidentMiniappGatewayPolicy.downstreamPath(path));
    }
    @Test void actionPathsHaveSeparateReadAndWriteScopes() {
        String path="/auth/wechat/cases/"+ID+"/actions";
        assertTrue(IncidentMiniappGatewayPolicy.allowed("GET",path)); assertTrue(IncidentMiniappGatewayPolicy.allowed("POST",path));
        assertFalse(IncidentMiniappGatewayPolicy.allowed("DELETE",path));
        org.junit.jupiter.api.Assertions.assertEquals("incident.case-action.read",IncidentMiniappGatewayPolicy.scope("GET",path));
        org.junit.jupiter.api.Assertions.assertEquals("incident.case-action.write",IncidentMiniappGatewayPolicy.scope("POST",path));
        assertTrue(IncidentMiniappGatewayPolicy.allowed("GET","/auth/wechat/rescues/"+ID+"/case"));
    }
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
