package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WechatRescueDraftControllerTest {
    @Test
    void preservesSafeDownstreamClientErrorSemantics() {
        assertEquals(HttpStatus.NOT_FOUND, WechatRescueDraftController.errorFor(new WechatRescueDraftClient.NotFound()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, WechatRescueDraftController.errorFor(new WechatRescueDraftClient.BadRequest()).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, WechatRescueDraftController.errorFor(new WechatSessionClient.Invalid()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, WechatRescueDraftController.errorFor(new WechatRescueDraftClient.Forbidden()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, WechatRescueDraftController.errorFor(new WechatRescueDraftClient.Conflict()).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, WechatRescueDraftController.errorFor(new WechatRescueDraftClient.Unavailable()).getStatusCode());
    }
}
