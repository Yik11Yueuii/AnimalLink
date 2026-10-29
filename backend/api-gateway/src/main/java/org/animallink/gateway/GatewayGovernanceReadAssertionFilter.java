package org.animallink.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class GatewayGovernanceReadAssertionFilter implements WebFilter, Ordered {
    private final GovernanceReadAssertionSigner signer;

    public GatewayGovernanceReadAssertionFilter(GovernanceReadAssertionSigner signer) {
        this.signer = signer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var binding = AdminGovernanceReadPolicy.binding(exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath());
        if (binding.isEmpty()) return chain.filter(exchange);
        Object value = exchange.getAttribute(GatewayAdminSessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof IdentitySessionClient.GatewayAuthenticatedPrincipal principal)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "ADMIN_SESSION_REQUIRED");
        }
        if (!"GOVERNANCE_ADMIN".equals(principal.systemRole())) {
            return reject(exchange, HttpStatus.FORBIDDEN, "ADMIN_PERMISSION_REQUIRED");
        }
        try {
            String assertion = signer.sign(principal, binding.get());
            var request = exchange.getRequest().mutate().headers(headers -> {
                headers.remove(HttpHeaders.AUTHORIZATION);
                headers.setBearerAuth(assertion);
            }).build();
            return chain.filter(exchange.mutate().request(request).build());
        } catch (GovernanceReadAssertionSigner.AssertionRejectedException rejected) {
            return reject(exchange, HttpStatus.FORBIDDEN, "ADMIN_PERMISSION_REQUIRED");
        } catch (GovernanceReadAssertionSigner.AssertionUnavailableException unavailable) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_IDENTITY_UNAVAILABLE");
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory().wrap(body)));
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 100; }
}
