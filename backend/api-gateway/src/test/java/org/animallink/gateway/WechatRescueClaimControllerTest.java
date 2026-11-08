package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WechatRescueClaimControllerTest {
    @Test void mapsSessionAndClaimFailuresWithoutExposingDetails() {
        var controller = new WechatRescueClaimController(null);
        assertEquals(HttpStatus.UNAUTHORIZED, controller.claim(null, "event", new WechatRescueClaimController.ClaimRequest("campus")).block().getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, WechatRescueClaimController.failure(new WechatSessionClient.Invalid()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, WechatRescueClaimController.failure(new WechatRescueDraftClient.Forbidden()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, WechatRescueClaimController.failure(new WechatRescueDraftClient.Conflict()).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, WechatRescueClaimController.failure(new WechatRescueDraftClient.Unavailable()).getStatusCode());
    }
}
