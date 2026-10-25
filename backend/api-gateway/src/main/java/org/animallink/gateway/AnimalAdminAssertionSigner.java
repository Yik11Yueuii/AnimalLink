package org.animallink.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class AnimalAdminAssertionSigner {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final String issuer;
    private final String subject;
    private final String audience;
    private final Duration lifetime;
    private final String currentKeyId;
    private final Map<String, byte[]> keys;
    private final boolean configurationValid;
    private final Clock clock;

    @Autowired
    public AnimalAdminAssertionSigner(
            @Value("${animallink.authentication.animal-admin-assertion.issuer:api-gateway}") String issuer,
            @Value("${animallink.authentication.animal-admin-assertion.subject:api-gateway}") String subject,
            @Value("${animallink.authentication.animal-admin-assertion.audience:animal-service}") String audience,
            @Value("${animallink.authentication.animal-admin-assertion.lifetime:PT15S}") Duration lifetime,
            @Value("${animallink.authentication.animal-admin-assertion.current-key-id:}") String currentKeyId,
            @Value("${animallink.authentication.animal-admin-assertion.keys:}") String configuredKeys) {
        this(issuer, subject, audience, lifetime, currentKeyId, configuredKeys, Clock.systemUTC());
    }

    AnimalAdminAssertionSigner(String issuer, String subject, String audience, Duration lifetime,
                               String currentKeyId, String configuredKeys, Clock clock) {
        this.issuer = requireIdentifier(issuer);
        this.subject = requireIdentifier(subject);
        this.audience = requireIdentifier(audience);
        this.lifetime = lifetime;
        this.currentKeyId = currentKeyId == null ? "" : currentKeyId.trim();
        this.clock = clock;
        Map<String, byte[]> parsed;
        boolean valid;
        try {
            parsed = parseKeys(configuredKeys);
            valid = lifetime != null && !lifetime.isZero() && !lifetime.isNegative()
                    && lifetime.compareTo(Duration.ofSeconds(30)) <= 0
                    && KEY_ID.matcher(this.currentKeyId).matches()
                    && parsed.containsKey(this.currentKeyId);
        } catch (IllegalArgumentException invalid) {
            parsed = Map.of();
            valid = false;
        }
        this.keys = parsed;
        this.configurationValid = valid;
    }

    public String sign(IdentitySessionClient.GatewayAuthenticatedPrincipal principal,
                       String requestMethod, String requestPath) {
        if (!configurationValid) throw new AssertionUnavailableException();
        if (principal == null || !"GOVERNANCE_ADMIN".equals(principal.systemRole())
                || !isUuid(principal.userId()) || !"GET".equals(requestMethod)
                || requestPath == null || requestPath.length() > 512) {
            throw new AssertionRejectedException();
        }
        try {
            Instant issuedAt = clock.instant();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .claim("target", audience)
                    .claim("actor_id", principal.userId())
                    .claim("system_role", principal.systemRole())
                    .claim("scope", Collections.singletonList(AdminCommunityReviewReadPolicy.SCOPE))
                    .claim("request_method", requestMethod)
                    .claim("request_path", requestPath)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(lifetime)))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .type(JOSEObjectType.JWT).keyID(currentKeyId).build(), claims);
            jwt.sign(new MACSigner(keys.get(currentKeyId)));
            return jwt.serialize();
        } catch (AssertionRejectedException rejected) {
            throw rejected;
        } catch (Exception failure) {
            throw new AssertionUnavailableException();
        }
    }

    private static Map<String, byte[]> parseKeys(String configuredKeys) {
        if (configuredKeys == null || configuredKeys.isBlank()) return Map.of();
        Map<String, byte[]> parsed = new HashMap<>();
        for (String entry : configuredKeys.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) throw new IllegalArgumentException();
            String keyId = entry.substring(0, separator).trim();
            if (!KEY_ID.matcher(keyId).matches() || parsed.containsKey(keyId)) {
                throw new IllegalArgumentException();
            }
            byte[] secret = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            if (secret.length < 32) throw new IllegalArgumentException();
            parsed.put(keyId, secret);
        }
        return Map.copyOf(parsed);
    }

    private static String requireIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException();
        return value;
    }

    private static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static final class AssertionRejectedException extends RuntimeException {
    }

    public static final class AssertionUnavailableException extends RuntimeException {
    }
}
