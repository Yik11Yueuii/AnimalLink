package org.animallink.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class GatewayAdminSessionAuthenticationFilter implements WebFilter, Ordered {
    public static final String PRINCIPAL_ATTRIBUTE = GatewayAdminSessionAuthenticationFilter.class.getName()
            + ".principal";

    private final AdminSessionCredentialResolver credentialResolver;
    private final AdminSessionValidator identitySessionClient;

    public GatewayAdminSessionAuthenticationFilter(AdminSessionCredentialResolver credentialResolver,
                                                   AdminSessionValidator identitySessionClient) {
        this.credentialResolver = credentialResolver;
        this.identitySessionClient = identitySessionClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange sanitized = withoutSessionCookie(exchange);
        String token;
        try {
            token = credentialResolver.resolve(exchange).orElse(null);
        } catch (AdminSessionCredentialResolver.InvalidSessionCredentialException invalid) {
            return authenticationFailure(sanitized, chain, HttpStatus.UNAUTHORIZED, "SESSION_INVALID");
        }
        if (token == null) return chain.filter(sanitized);

        return identitySessionClient.validate(token)
                .flatMap(principal -> {
                    sanitized.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
                    return chain.filter(sanitized);
                })
                .onErrorResume(IdentitySessionClient.SessionUnauthenticatedException.class,
                        error -> authenticationFailure(sanitized, chain, HttpStatus.UNAUTHORIZED, "SESSION_INVALID"))
                .onErrorResume(IdentitySessionClient.SessionForbiddenException.class,
                        error -> authenticationFailure(sanitized, chain, HttpStatus.FORBIDDEN, "SESSION_FORBIDDEN"))
                .onErrorResume(IdentitySessionClient.IdentitySessionUnavailableException.class,
                        error -> authenticationFailure(sanitized, chain, HttpStatus.SERVICE_UNAVAILABLE,
                                "IDENTITY_SESSION_UNAVAILABLE"));
    }

    private Mono<Void> authenticationFailure(ServerWebExchange exchange, WebFilterChain chain,
                                             HttpStatus protectedStatus, String code) {
        if (ExternalIdentityBoundaryFilter.isPublicRead(exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath())) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(protectedStatus);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private ServerWebExchange withoutSessionCookie(ServerWebExchange exchange) {
        if (!exchange.getRequest().getCookies().containsKey(credentialResolver.cookieName())) return exchange;
        List<String> retained = new ArrayList<>();
        exchange.getRequest().getCookies().forEach((name, cookies) -> {
            if (!credentialResolver.cookieName().equals(name)) {
                for (HttpCookie cookie : cookies) retained.add(cookie.toString());
            }
        });
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(HttpHeaders.COOKIE);
            if (!retained.isEmpty()) headers.set(HttpHeaders.COOKIE, String.join("; ", retained));
        }).build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
