package org.animallink.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.animallink.identity.api.InternalWechatSessionController;
import org.animallink.identity.application.WechatAuthenticationService;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.animallink.identity.domain.WechatAuthenticationRepository;
import org.animallink.identity.infrastructure.InternalSessionServiceAuthenticationFilter;
import org.animallink.identity.infrastructure.SecureOpaqueSessionTokenCodec;
import org.animallink.identity.infrastructure.ServiceAssertionVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class InternalWechatSessionControllerTest {
    @Test
    void mapsClassifiedExchangeFailuresWithoutCreatingAUserOrSession() {
        for (Failure failure : List.of(
                new Failure(new WechatCodeExchangeClient.RejectedCodeException(), HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID"),
                new Failure(new WechatCodeExchangeClient.RiskBlockedException(), HttpStatus.FORBIDDEN, "WECHAT_USER_BLOCKED"),
                new Failure(new WechatCodeExchangeClient.RateLimitedException(), HttpStatus.TOO_MANY_REQUESTS, "WECHAT_RATE_LIMITED"),
                new Failure(new WechatCodeExchangeClient.UnavailableException(), HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_EXCHANGE_UNAVAILABLE"),
                new Failure(new IllegalStateException("upstream failure"), HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_UNAVAILABLE"))) {
            var repository = mock(WechatAuthenticationRepository.class);
            var service = service(code -> { throw failure.exception(); }, repository);
            var response = new InternalWechatSessionController(service).login(
                    new InternalWechatSessionController.LoginRequest("fake-code-123"), gatewayRequest());

            assertThat(response.getStatusCode()).isEqualTo(failure.status());
            assertThat(response.getBody()).isEqualTo(new InternalWechatSessionController.ErrorResponse(failure.code()));
            verifyNoInteractions(repository);
        }
    }

    @Test
    void requiresAVerifiedGatewayAssertionBeforeCodeExchange() {
        var repository = mock(WechatAuthenticationRepository.class);
        var service = service(code -> { throw new AssertionError("code exchange must not run"); }, repository);
        var response = new InternalWechatSessionController(service).login(
                new InternalWechatSessionController.LoginRequest("fake-code-123"), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    private static WechatAuthenticationService service(WechatCodeExchangeClient exchange,
                                                        WechatAuthenticationRepository repository) {
        return new WechatAuthenticationService(exchange, repository, new SecureOpaqueSessionTokenCodec(),
                () -> Instant.parse("2026-10-31T00:00:00Z"), "wx-test-app",
                Duration.ofDays(7), Duration.ofDays(30));
    }

    private static MockHttpServletRequest gatewayRequest() {
        var request = new MockHttpServletRequest();
        request.setAttribute(InternalSessionServiceAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                new ServiceAssertionVerifier.AuthenticatedService("api-gateway", "api-gateway",
                        "identity-service", "assertion-id", null));
        return request;
    }

    private record Failure(RuntimeException exception, HttpStatus status, String code) {
    }
}
