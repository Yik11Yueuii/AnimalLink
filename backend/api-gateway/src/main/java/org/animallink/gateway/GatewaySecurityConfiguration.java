package org.animallink.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {
    static final String CSRF_SESSION_COOKIE_NAME = "__Host-ANIMALLINK-CSRF-SESSION";
    static final String CSRF_HEADER_NAME = "X-CSRF-TOKEN";

    @Bean
    SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http) {
        var repository = new WebSessionServerCsrfTokenRepository();
        repository.setHeaderName(CSRF_HEADER_NAME);

        return http
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(repository)
                        .requireCsrfProtectionMatcher(adminMutationMatcher()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .build();
    }

    private ServerWebExchangeMatcher adminMutationMatcher() {
        return exchange -> {
            String path = exchange.getRequest().getURI().getPath();
            boolean protectedMutation = (HttpMethod.POST.equals(exchange.getRequest().getMethod())
                    && (AdminAuthenticationController.LOGIN_PATH.equals(path)
                    || AdminAuthenticationController.LOGOUT_PATH.equals(path)))
                    || AdminCommunityReviewWritePolicy.matches(exchange.getRequest().getMethod(), path);
            return protectedMutation
                    ? ServerWebExchangeMatcher.MatchResult.match()
                    : ServerWebExchangeMatcher.MatchResult.notMatch();
        };
    }
}
