package org.animallink.gateway;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class AdminSessionCookieManager {
    private final AdminSessionCredentialResolver credentialResolver;

    public AdminSessionCookieManager(AdminSessionCredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    public ResponseCookie active(String rawToken, Instant idleExpiresAt, Instant absoluteExpiresAt) {
        Instant expiry = idleExpiresAt.isBefore(absoluteExpiresAt) ? idleExpiresAt : absoluteExpiresAt;
        Duration maxAge = Duration.between(Instant.now(), expiry);
        if (maxAge.isNegative() || maxAge.isZero()) maxAge = Duration.ofSeconds(1);
        return base(rawToken).maxAge(maxAge).build();
    }

    public ResponseCookie expired() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(credentialResolver.cookieName(), value)
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/");
    }
}
