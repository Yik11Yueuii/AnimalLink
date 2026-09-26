package org.animallink.gateway;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(TraceIdGlobalFilter.class);
    private static final String HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9_-]{1,64}")
                ? incoming : UUID.randomUUID().toString();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, traceId);
            return Mono.empty();
        });
        ServerWebExchange traced = exchange.mutate().request(
                exchange.getRequest().mutate().headers(headers -> headers.set(HEADER, traceId)).build()
        ).build();
        log.info("Gateway request traceId={} method={} path={}", traceId,
                exchange.getRequest().getMethod(), exchange.getRequest().getPath());
        return chain.filter(traced);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
