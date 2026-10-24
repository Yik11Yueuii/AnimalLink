package org.animallink.gateway;

import reactor.core.publisher.Mono;

public interface AdminAuthenticationClient extends AdminSessionValidator {
    Mono<IdentitySessionClient.IssuedAdminSession> login(String username, String password,
                                                         String trustedSource);

    Mono<IdentitySessionClient.RevocationResult> revoke(String rawSessionToken);
}
