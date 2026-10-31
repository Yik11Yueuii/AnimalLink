package org.animallink.gateway;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * This is an ingress boundary, not an authentication provider. No browser
 * assertion is trusted; future verified identity must be injected after validation.
 */
@Component
public class ExternalIdentityBoundaryFilter implements WebFilter, Ordered {
    private static final Set<String> DEV_PROFILES = Set.of("local", "dev", "test");
    // Complete path matches only; no prefix grants access to adjacent resources.
    private static final String UUID_SEGMENT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    static final List<Pattern> PUBLIC_READS = List.of(
            Pattern.compile("^/identity/api/v1/campuses$"),
            Pattern.compile("^/identity/api/v1/campuses/" + UUID_SEGMENT + "$"),
            Pattern.compile("^/identity/api/v1/universities$"),
            Pattern.compile("^/identity/api/v1/universities/" + UUID_SEGMENT + "/community$"),
            Pattern.compile("^/animal/api/v1/public/animals$"),
            Pattern.compile("^/animal/api/v1/public/animals/" + UUID_SEGMENT + "$"),
            Pattern.compile("^/animal/api/v1/public/campuses/" + UUID_SEGMENT + "/feed$"),
            Pattern.compile("^/animal/api/v1/public/posts/" + UUID_SEGMENT + "$"),
            Pattern.compile("^/animal/api/v1/public/posts/" + UUID_SEGMENT + "/comments$"),
            Pattern.compile("^/incident/api/v1/public/rescues$"),
            Pattern.compile("^/incident/api/v1/public/rescues/" + UUID_SEGMENT + "$"),
            Pattern.compile("^/incident/api/v1/cases/" + UUID_SEGMENT + "/support/expenses$"),
            Pattern.compile("^/actuator/(?:health(?:/(?:liveness|readiness))?|info)$"));
    private final boolean devSimulation;

    public ExternalIdentityBoundaryFilter(Environment environment) {
        devSimulation = environment.getProperty("animallink.gateway.allow-dev-user-header", Boolean.class, false);
        if (devSimulation) {
            String[] profiles = environment.getActiveProfiles();
            String address = environment.getProperty("server.address", "");
            if (profiles.length == 0 || Arrays.stream(profiles).anyMatch(p -> !DEV_PROFILES.contains(p))
                    || !isLoopbackAddress(address)) {
                throw new IllegalStateException("Development identity requires isolated profiles and a loopback-only gateway");
            }
        }
    }

    private static boolean isLoopbackAddress(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        boolean isolatedRequest = devSimulation && remote != null && remote.getAddress() != null
                && remote.getAddress().isLoopbackAddress();
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();
        // No CORS policy is configured: OPTIONS, including preflight, is denied.
        // HEAD is allowed only for precisely the same verified public resources as GET.
        boolean publicRead = isPublicRead(method, path);
        boolean authenticationEndpoint = isAuthenticationEndpoint(method, path);
        boolean authenticatedAdminRequest = (AdminCommunityReviewReadPolicy.matches(method, path)
                || AdminCommunityReviewWritePolicy.matches(method, path)
                || AdminGovernanceReadPolicy.matches(method, path))
                && exchange.getAttribute(GatewayAdminSessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
                instanceof IdentitySessionClient.GatewayAuthenticatedPrincipal principal
                && "GOVERNANCE_ADMIN".equals(principal.systemRole());
        if (!publicRead && !authenticationEndpoint && !authenticatedAdminRequest && !isolatedRequest) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        List<String> suppliedIds = exchange.getRequest().getHeaders().get("X-User-Id");
        String devId = isolatedRequest && suppliedIds != null && suppliedIds.size() == 1
                && isUuid(suppliedIds.getFirst()) ? suppliedIds.getFirst() : null;
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.keySet().removeIf(name -> isIdentityClaim(name)
                    && !(WechatAuthenticationController.SESSION_HEADER.equalsIgnoreCase(name)
                    && isWechatSessionEndpoint(method, path)));
            if (devId != null) headers.set("X-User-Id", devId);
            // Future authenticated assertions must be added here only after verification.
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    static boolean isPublicRead(HttpMethod method, String path) {
        return (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method))
                && PUBLIC_READS.stream().anyMatch(rule -> rule.matcher(path).matches());
    }

    static boolean isAuthenticationEndpoint(HttpMethod method, String path) {
        return (HttpMethod.GET.equals(method) && (AdminAuthenticationController.CSRF_PATH.equals(path)
                || AdminAuthenticationController.SESSION_PATH.equals(path)))
                || (HttpMethod.POST.equals(method) && (AdminAuthenticationController.LOGIN_PATH.equals(path)
                || AdminAuthenticationController.LOGOUT_PATH.equals(path)))
                || (HttpMethod.POST.equals(method) && WechatAuthenticationController.LOGIN_PATH.equals(path))
                || (HttpMethod.GET.equals(method) && WechatAuthenticationController.ME_PATH.equals(path))
                || (HttpMethod.POST.equals(method) && WechatAuthenticationController.LOGOUT_PATH.equals(path))
                || (HttpMethod.GET.equals(method) && WechatCampusIdentityController.SNAPSHOT_PATH.equals(path))
                || (HttpMethod.POST.equals(method) && WechatCampusIdentityController.APPLICATIONS_PATH.equals(path));
    }

    private static boolean isWechatSessionEndpoint(HttpMethod method, String path) {
        return (HttpMethod.GET.equals(method) && WechatAuthenticationController.ME_PATH.equals(path))
                || (HttpMethod.POST.equals(method) && WechatAuthenticationController.LOGOUT_PATH.equals(path))
                || (HttpMethod.GET.equals(method) && WechatCampusIdentityController.SNAPSHOT_PATH.equals(path))
                || (HttpMethod.POST.equals(method) && WechatCampusIdentityController.APPLICATIONS_PATH.equals(path));
    }

    private static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isIdentityClaim(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals(HttpHeaders.AUTHORIZATION.toLowerCase(Locale.ROOT))
                || n.equals("proxy-authorization") || n.equals("x-forwarded-user")
                || n.equals("x-remote-user") || n.equals("x-original-user")
                || n.equals("x-user") || n.equals("x-userid") || n.equals("x-identity")
                || n.equals("x-miniapp-session")
                || n.equals("x-principal") || n.equals("x-authenticated") || n.equals("x-auth")
                || n.startsWith("x-user-") || n.startsWith("x-internal-")
                || n.startsWith("x-auth-") || n.startsWith("x-authenticated-")
                || n.startsWith("x-principal-") || n.startsWith("x-identity-") || n.startsWith("x-trusted-");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
