package org.animallink.intelligence.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.intelligence.application.IdentityGateway;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class HttpIdentityGateway implements IdentityGateway {
    private static final String USER_ID_HEADER = "X-User-Id";
    private final RestClient client;
    private final HttpServletRequest request;

    public HttpIdentityGateway(@Qualifier("identityRestClient") RestClient identityRestClient,
                               HttpServletRequest request) {
        this.client = identityRestClient;
        this.request = request;
    }

    @Override
    public CurrentUser requireCurrentUser() {
        try {
            RestClient.RequestHeadersSpec<?> spec = client.get().uri("/api/v1/users/me")
                    .header("X-Trace-Id", traceId());
            String userId = request.getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) spec = spec.header(USER_ID_HEADER, userId);
            UserPayload value = spec.retrieve().body(UserPayload.class);
            if (value == null) throw new Unauthorized("无法确认当前用户");
            return new CurrentUser(value.id(), value.displayName(), value.accountStatus(), value.systemRole());
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new Unauthorized("需要登录");
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new Forbidden("身份服务拒绝当前请求");
        } catch (Unauthorized | Forbidden exception) {
            throw exception;
        } catch (ResourceAccessException | RestClientResponseException exception) {
            throw unavailable();
        }
    }

    @Override
    public MembershipFact campusMembership(String userId, String campusId) {
        try {
            MembershipPayload value = client.get()
                    .uri("/internal/v1/users/{userId}/campus-memberships/{campusId}", userId, campusId)
                    .header("X-Trace-Id", traceId()).retrieve().body(MembershipPayload.class);
            if (value == null) throw unavailable();
            return new MembershipFact(value.exists(), value.membershipType(), value.status());
        } catch (ResourceAccessException | RestClientResponseException exception) {
            throw unavailable();
        }
    }

    private DependencyUnavailable unavailable() {
        return new DependencyUnavailable("identity-service 暂不可用，AI 写操作已拒绝");
    }

    private String traceId() {
        String value = MDC.get("traceId");
        return value == null ? "intelligence-service" : value;
    }

    private record UserPayload(String id, String displayName, String accountStatus, String systemRole) {}
    private record MembershipPayload(boolean exists, String membershipType, String status) {}
}
