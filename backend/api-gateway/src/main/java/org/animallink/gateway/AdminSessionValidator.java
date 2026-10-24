package org.animallink.gateway;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface AdminSessionValidator {
    Mono<IdentitySessionClient.GatewayAuthenticatedPrincipal> validate(String rawSessionToken);
}
