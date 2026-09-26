package org.animallink.animal.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.animal.application.IdentityGateway;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.animallink.animal.domain.ForbiddenException;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.domain.StateConflictException;
import org.animallink.animal.domain.UnauthorizedException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpIdentityGateway implements IdentityGateway {
    static final String USER_ID_HEADER = "X-User-Id";
    private final RestClient restClient;
    private final HttpServletRequest request;

    public HttpIdentityGateway(RestClient identityRestClient, HttpServletRequest request) {
        this.restClient = identityRestClient;
        this.request = request;
    }

    @Override
    public void requireActiveCampus(String campusId) {
        try {
            CampusPayload campus = restClient.get()
                    .uri("/internal/v1/campuses/{campusId}", campusId)
                    .header("X-Trace-Id", traceId())
                    .retrieve()
                    .body(CampusPayload.class);
            if (campus == null) {
                throw new ResourceNotFoundException("Campus 不存在");
            }
            if (!"ACTIVE".equals(campus.status())) {
                throw new StateConflictException("Campus 未启用");
            }
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResourceNotFoundException("Campus 不存在");
        } catch (ResourceNotFoundException | StateConflictException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw unavailable();
        } catch (RestClientResponseException exception) {
            throw unavailable();
        }
    }

    @Override
    public CurrentUser requireCurrentUser() {
        try {
            RestClient.RequestHeadersSpec<?> requestSpec = restClient.get()
                    .uri("/api/v1/users/me")
                    .header("X-Trace-Id", traceId());
            String userId = request.getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) {
                requestSpec = requestSpec.header(USER_ID_HEADER, userId);
            }
            UserPayload user = requestSpec.retrieve().body(UserPayload.class);
            if (user == null) {
                throw new UnauthorizedException("无法确认当前用户");
            }
            return new CurrentUser(user.id(), user.accountStatus(), user.systemRole());
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new UnauthorizedException("需要登录");
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new ForbiddenException("身份服务拒绝当前请求");
        } catch (UnauthorizedException | ForbiddenException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw unavailable();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new UnauthorizedException("需要登录");
            }
            if (exception.getStatusCode().value() == 403) {
                throw new ForbiddenException("身份服务拒绝当前请求");
            }
            throw unavailable();
        }
    }

    private DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException("identity-service 暂不可用，敏感写操作已拒绝");
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "animal-service" : traceId;
    }

    private record CampusPayload(String id, String status) {
    }

    private record UserPayload(String id, String accountStatus, String systemRole) {
    }
}
