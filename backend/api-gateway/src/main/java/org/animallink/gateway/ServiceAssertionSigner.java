package org.animallink.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class ServiceAssertionSigner {
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
    public ServiceAssertionSigner(
            @Value("${animallink.authentication.service-assertion.issuer:api-gateway}") String issuer,
            @Value("${animallink.authentication.service-assertion.subject:api-gateway}") String subject,
            @Value("${animallink.authentication.service-assertion.audience:identity-service}") String audience,
            @Value("${animallink.authentication.service-assertion.lifetime:PT15S}") Duration lifetime,
            @Value("${animallink.authentication.service-assertion.current-key-id:}") String currentKeyId,
            @Value("${animallink.authentication.service-assertion.keys:}") String configuredKeys) {
        this(issuer, subject, audience, lifetime, currentKeyId, configuredKeys, Clock.systemUTC());
    }

    ServiceAssertionSigner(String issuer, String subject, String audience, Duration lifetime,
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
            valid = lifetime != null && !lifetime.isNegative() && !lifetime.isZero()
                    && lifetime.compareTo(Duration.ofSeconds(30)) <= 0
                    && KEY_ID.matcher(this.currentKeyId).matches()
                    && parsed.containsKey(this.currentKeyId);
        } catch (IllegalArgumentException invalidConfiguration) {
            parsed = Map.of();
            valid = false;
        }
        this.keys = parsed;
        this.configurationValid = valid;
    }

    public String sign() {
        return signForActor(null);
    }

    /** Binds a gateway-verified ordinary-user actor to the short-lived assertion. */
    public String signForActor(String actorUserId) {
        if (!configurationValid) throw new ServiceAssertionUnavailableException();
        try {
            String actor = requireUuidOrNull(actorUserId);
            Instant issuedAt = clock.instant();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .claim("target", audience)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(lifetime)))
                    .jwtID(UUID.randomUUID().toString());
            if (actor != null) claims.claim("actor", actor);
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .type(JOSEObjectType.JWT).keyID(currentKeyId).build(), claims.build());
            jwt.sign(new MACSigner(keys.get(currentKeyId)));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new ServiceAssertionUnavailableException();
        }
    }

    private static Map<String, byte[]> parseKeys(String configuredKeys) {
        if (configuredKeys == null || configuredKeys.isBlank()) return Map.of();
        Map<String, byte[]> parsed = new HashMap<>();
        for (String entry : configuredKeys.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) throw new IllegalArgumentException();
            String keyId = entry.substring(0, separator).trim();
            if (!KEY_ID.matcher(keyId).matches() || parsed.containsKey(keyId)) throw new IllegalArgumentException();
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

    private static String requireUuidOrNull(String value) {
        if (value == null) return null;
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (IllegalArgumentException invalid) {
            throw new ServiceAssertionUnavailableException();
        }
    }

    public static final class ServiceAssertionUnavailableException extends RuntimeException {
    }
}
