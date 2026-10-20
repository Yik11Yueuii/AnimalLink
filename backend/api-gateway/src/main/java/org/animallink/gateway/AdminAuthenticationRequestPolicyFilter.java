package org.animallink.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AdminAuthenticationRequestPolicyFilter implements WebFilter, Ordered {
    private final Set<String> allowedOrigins;
    private final long maximumLoginBodyBytes;

    public AdminAuthenticationRequestPolicyFilter(
            @Value("${animallink.authentication.browser.allowed-origins:}") String configuredOrigins,
            @Value("${animallink.authentication.browser.maximum-login-body-bytes:4096}") long maximumLoginBodyBytes) {
        this.allowedOrigins = Arrays.stream(configuredOrigins == null ? new String[0] : configuredOrigins.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(AdminAuthenticationRequestPolicyFilter::configuredOrigin)
                .collect(Collectors.toUnmodifiableSet());
        if (maximumLoginBodyBytes < 256 || maximumLoginBodyBytes > 16384) {
            throw new IllegalArgumentException("Administrator login body limit must be 256-16384 bytes");
        }
        this.maximumLoginBodyBytes = maximumLoginBodyBytes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isStateChangingAuthenticationRequest(exchange)) return chain.filter(exchange);

        var origins = exchange.getRequest().getHeaders().get(HttpHeaders.ORIGIN);
        String fetchSite = exchange.getRequest().getHeaders().getFirst("Sec-Fetch-Site");
        if (allowedOrigins.isEmpty() || origins == null || origins.size() != 1
                || !allowedOrigins.contains(requestOrigin(origins.getFirst())) || !"same-origin".equals(fetchSite)) {
            return reject(exchange, HttpStatus.FORBIDDEN, "BROWSER_ORIGIN_REJECTED");
        }

        if (AdminAuthenticationController.LOGIN_PATH.equals(exchange.getRequest().getURI().getPath())) {
            MediaType contentType = exchange.getRequest().getHeaders().getContentType();
            if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                return reject(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "CONTENT_TYPE_REQUIRED");
            }
            long length = exchange.getRequest().getHeaders().getContentLength();
            if (length < 0) return reject(exchange, HttpStatus.LENGTH_REQUIRED, "CONTENT_LENGTH_REQUIRED");
            if (length > maximumLoginBodyBytes) {
                return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE");
            }
        }
        return chain.filter(exchange);
    }

    private static boolean isStateChangingAuthenticationRequest(ServerWebExchange exchange) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) return false;
        String path = exchange.getRequest().getURI().getPath();
        return AdminAuthenticationController.LOGIN_PATH.equals(path)
                || AdminAuthenticationController.LOGOUT_PATH.equals(path);
    }

    private static String configuredOrigin(String value) {
        String normalized = normalizeOrigin(value);
        if (normalized == null) throw new IllegalArgumentException("Administrator origin must be an HTTPS origin");
        return normalized;
    }

    private static String requestOrigin(String value) {
        String normalized = normalizeOrigin(value);
        return normalized == null ? "invalid:" : normalized;
    }

    private static String normalizeOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort();
            return "https://" + uri.getHost().toLowerCase() + (port == -1 || port == 443 ? "" : ":" + port);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}
