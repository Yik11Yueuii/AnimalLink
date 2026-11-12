package org.animallink.adoption.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.adoption.application.IdentityGateway;
import org.animallink.adoption.domain.DependencyUnavailableException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.animallink.adoption.domain.ApplicantNotEligibleException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpIdentityGateway implements IdentityGateway {
    private final RestClient client; private final HttpServletRequest request;
    public HttpIdentityGateway(@Qualifier("identityRestClient") RestClient client, HttpServletRequest request) { this.client = client; this.request = request; }
    public CurrentUser requireCurrentUser() {
        try {
            Object adminPrincipal = request.getAttribute(AdoptionAdminAssertionFilter.PRINCIPAL);
            if (adminPrincipal instanceof AdoptionAdminAssertionVerifier.AuthenticatedAdmin admin) {
                UserSummary[] users = client.post().uri("/internal/v1/users/summaries")
                        .body(java.util.Map.of("userIds", java.util.List.of(admin.userId())))
                        .retrieve().body(UserSummary[].class);
                if (users == null || users.length != 1 || !admin.userId().equals(users[0].id())
                        || !"ACTIVE".equals(users[0].accountStatus()))
                    throw new ListingAccessDeniedException("管理员账号已失效");
                return new CurrentUser(admin.userId(), "ACTIVE", "GOVERNANCE_ADMIN");
            }
            Object principal = request.getAttribute(AdoptionMiniappAssertionFilter.PRINCIPAL);
            if (principal instanceof AdoptionMiniappAssertionVerifier.AuthenticatedMiniappUser actor) {
                UserSummary[] users = client.post().uri("/internal/v1/users/summaries")
                        .body(java.util.Map.of("userIds", java.util.List.of(actor.userId())))
                        .retrieve().body(UserSummary[].class);
                if (users == null || users.length != 1 || !actor.userId().equals(users[0].id()) || !"ACTIVE".equals(users[0].accountStatus()))
                    throw new ListingAccessDeniedException("无法确认当前有效用户");
                return new CurrentUser(users[0].id(), users[0].accountStatus(), "USER");
            }
            RestClient.RequestHeadersSpec<?> spec = client.get().uri("/api/v1/users/me");
            String userId = request.getHeader("X-User-Id");
            if (userId != null && !userId.isBlank()) spec = spec.header("X-User-Id", userId);
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) spec = spec.header(HttpHeaders.AUTHORIZATION, authorization);
            UserPayload payload = spec.retrieve().body(UserPayload.class);
            if (payload == null) throw new ListingAccessDeniedException("无法确认当前用户");
            return new CurrentUser(payload.id(), payload.accountStatus(), payload.systemRole());
        } catch (HttpClientErrorException.Unauthorized e) { throw new ListingAccessDeniedException("需要登录");
        } catch (HttpClientErrorException.Forbidden e) { throw new ListingAccessDeniedException("身份服务拒绝当前请求");
        } catch (ListingAccessDeniedException e) { throw e;
        } catch (ResourceAccessException | RestClientResponseException e) { throw new DependencyUnavailableException("identity-service 暂不可用"); }
    }
    public CurrentUser requireEligibleApplicant() {
        if (request.getAttribute(AdoptionMiniappAssertionFilter.PRINCIPAL) instanceof AdoptionMiniappAssertionVerifier.AuthenticatedMiniappUser) {
            CurrentUser user = requireCurrentUser();
            try {
                ActiveMembership fact = client.get().uri("/internal/v1/users/{userId}/active-campus-membership", user.id()).retrieve().body(ActiveMembership.class);
                if (fact == null) throw new DependencyUnavailableException("identity-service 返回无效成员资格");
                if (!fact.active()) throw new ApplicantNotEligibleException("需要至少一个有效校园成员资格");
                return user;
            } catch (ResourceAccessException | RestClientResponseException e) { throw new DependencyUnavailableException("identity-service 暂不可用"); }
        }
        CurrentUser user;
        try { user = requireCurrentUser(); }
        catch (ListingAccessDeniedException | DependencyUnavailableException e) { throw new ApplicantNotEligibleException("需要已登录的有效账户"); }
        if (!"ACTIVE".equals(user.accountStatus())) throw new ApplicantNotEligibleException("账户未激活");
        try {
            RestClient.RequestHeadersSpec<?> spec = client.get().uri("/api/v1/users/me/campus-memberships");
            spec = forwardCredentials(spec);
            MembershipPayload[] memberships = spec.retrieve().body(MembershipPayload[].class);
            if (memberships == null || java.util.Arrays.stream(memberships).noneMatch(m -> "ACTIVE".equals(m.status()))) throw new ApplicantNotEligibleException("需要至少一个有效校园成员资格");
            return user;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) { throw new ApplicantNotEligibleException("无法确认申请资格");
        } catch (ApplicantNotEligibleException e) { throw e;
        } catch (ResourceAccessException | RestClientResponseException e) { throw new ApplicantNotEligibleException("无法确认申请资格"); }
    }
    private RestClient.RequestHeadersSpec<?> forwardCredentials(RestClient.RequestHeadersSpec<?> spec) {
        String userId = request.getHeader("X-User-Id"); if (userId != null && !userId.isBlank()) spec = spec.header("X-User-Id", userId);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION); if (authorization != null && !authorization.isBlank()) spec = spec.header(HttpHeaders.AUTHORIZATION, authorization);
        return spec;
    }
    private record ActiveMembership(boolean active) { }
    private record UserSummary(String id, String accountStatus) { }
    private record UserPayload(String id, String accountStatus, String systemRole) { }
    private record MembershipPayload(String status) { }
}
