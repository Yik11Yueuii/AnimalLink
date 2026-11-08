package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WechatRescueClaimControllerTest {
    @Test void mapsSessionAndClaimFailuresWithoutExposingDetails() {
        var controller = new WechatRescueClaimController(null);
        assertEquals(HttpStatus.UNAUTHORIZED, controller.claim(null, "event", new WechatRescueClaimController.ClaimRequest("campus")).block().getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, WechatRescueClaimController.failure(new WechatSessionClient.Invalid()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, WechatRescueClaimController.failure(new WechatRescueDraftClient.BadRequest()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, WechatRescueClaimController.failure(new WechatRescueDraftClient.Forbidden()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, WechatRescueClaimController.failure(new WechatRescueDraftClient.Conflict()).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, WechatRescueClaimController.failure(new WechatRescueDraftClient.Unavailable()).getStatusCode());
    }
    @Test void incidentAuthenticationFailuresAreNotSessionFailures() {
        var unauthorized = org.springframework.web.reactive.function.client.ClientResponse.create(HttpStatus.UNAUTHORIZED).build();
        var forbiddenAssertion = org.springframework.web.reactive.function.client.ClientResponse.create(HttpStatus.FORBIDDEN).header("Content-Type", "application/json").body("{\"code\":\"MINIAPP_USER_REQUIRED\"}").build();
        var forbiddenVolunteer = org.springframework.web.reactive.function.client.ClientResponse.create(HttpStatus.FORBIDDEN).header("Content-Type", "application/json").body("{\"code\":\"ACTIVE_VOLUNTEER_REQUIRED\"}").build();
        org.junit.jupiter.api.Assertions.assertThrows(WechatRescueDraftClient.Unavailable.class, () -> WechatRescueDraftClient.result(unauthorized, Object.class).block());
        org.junit.jupiter.api.Assertions.assertThrows(WechatRescueDraftClient.Unavailable.class, () -> WechatRescueDraftClient.result(forbiddenAssertion, Object.class).block());
        org.junit.jupiter.api.Assertions.assertThrows(WechatRescueDraftClient.Forbidden.class, () -> WechatRescueDraftClient.result(forbiddenVolunteer, Object.class).block());
    }
}
