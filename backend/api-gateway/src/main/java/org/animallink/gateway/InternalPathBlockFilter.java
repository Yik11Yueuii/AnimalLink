package org.animallink.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class InternalPathBlockFilter implements WebFilter, Ordered {
    private static final Pattern INTERNAL_PATH = Pattern.compile(
            "^/(identity|animal|incident|adoption|intelligence)/internal(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMBIGUOUS_ENCODING = Pattern.compile("%(?:25|2f|5c|2e|3b)", Pattern.CASE_INSENSITIVE);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String raw = exchange.getRequest().getURI().getRawPath();
        // Reject ambiguous paths before route matching, not only after a route has matched.
        if (raw == null || raw.contains("%") || AMBIGUOUS_ENCODING.matcher(raw).find()
                || raw.contains("//") || raw.contains("\\") || raw.contains(";")
                || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..")
                || INTERNAL_PATH.matcher(path.toLowerCase(Locale.ROOT)).find()) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
