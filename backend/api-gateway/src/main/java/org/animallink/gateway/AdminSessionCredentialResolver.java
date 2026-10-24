package org.animallink.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class AdminSessionCredentialResolver {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final String cookieName;

    public AdminSessionCredentialResolver(
            @Value("${animallink.authentication.identity-session.cookie-name:ANIMALLINK_ADMIN_SESSION}")
            String cookieName) {
        if (cookieName == null || !cookieName.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid administrator session cookie name");
        }
        this.cookieName = cookieName;
    }

    public Optional<String> resolve(ServerWebExchange exchange) {
        List<HttpCookie> cookies = exchange.getRequest().getCookies().get(cookieName);
        if (cookies == null || cookies.isEmpty()) return Optional.empty();
        if (cookies.size() != 1 || !TOKEN.matcher(cookies.getFirst().getValue()).matches()) {
            throw new InvalidSessionCredentialException();
        }
        return Optional.of(cookies.getFirst().getValue());
    }

    public String cookieName() {
        return cookieName;
    }

    public static final class InvalidSessionCredentialException extends RuntimeException {
    }
}
