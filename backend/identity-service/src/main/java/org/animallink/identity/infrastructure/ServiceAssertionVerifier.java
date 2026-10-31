package org.animallink.identity.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.UUID;

@Component
public class ServiceAssertionVerifier {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TOKEN_ID = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final String expectedIssuer;
    private final String expectedSubject;
    private final String expectedAudience;
    private final Duration maximumLifetime;
    private final Duration clockSkew;
    private final int replayCapacity;
    private final Map<String, byte[]> keys;
    private final boolean configurationValid;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> replayedAssertions = new ConcurrentHashMap<>();

    @Autowired
    public ServiceAssertionVerifier(
            @Value("${animallink.authentication.service-assertion.expected-issuer:api-gateway}") String expectedIssuer,
            @Value("${animallink.authentication.service-assertion.expected-subject:api-gateway}") String expectedSubject,
            @Value("${animallink.authentication.service-assertion.expected-audience:identity-service}") String expectedAudience,
            @Value("${animallink.authentication.service-assertion.maximum-lifetime:PT30S}") Duration maximumLifetime,
            @Value("${animallink.authentication.service-assertion.clock-skew:PT5S}") Duration clockSkew,
            @Value("${animallink.authentication.service-assertion.replay-capacity:10000}") int replayCapacity,
            @Value("${animallink.authentication.service-assertion.keys:}") String configuredKeys) {
        this(expectedIssuer, expectedSubject, expectedAudience, maximumLifetime, clockSkew,
                replayCapacity, configuredKeys, Clock.systemUTC());
    }

    ServiceAssertionVerifier(String expectedIssuer, String expectedSubject, String expectedAudience,
                             Duration maximumLifetime, Duration clockSkew, int replayCapacity,
                             String configuredKeys, Clock clock) {
        this.expectedIssuer = requireIdentifier(expectedIssuer);
        this.expectedSubject = requireIdentifier(expectedSubject);
        this.expectedAudience = requireIdentifier(expectedAudience);
        this.maximumLifetime = requirePositive(maximumLifetime);
        this.clockSkew = clockSkew == null || clockSkew.isNegative() ? Duration.ZERO : clockSkew;
        this.replayCapacity = replayCapacity > 0 ? replayCapacity : 1;
        this.clock = clock;
        Map<String, byte[]> parsed;
        boolean valid;
        try {
            parsed = parseKeys(configuredKeys);
            valid = !parsed.isEmpty();
        } catch (IllegalArgumentException invalidConfiguration) {
            parsed = Map.of();
            valid = false;
        }
        this.keys = parsed;
        this.configurationValid = valid;
    }

    public AuthenticatedService verify(String authorization) {
        if (!configurationValid) {
            throw new ServiceAssertionUnavailableException();
        }
        if (authorization == null || authorization.length() > 4096
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ServiceAssertionRejectedException();
        }
        String compact = authorization.substring(7).trim();
        try {
            SignedJWT jwt = SignedJWT.parse(compact);
            String keyId = jwt.getHeader().getKeyID();
            byte[] key = keyId == null ? null : keys.get(keyId);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !JOSEObjectType.JWT.equals(jwt.getHeader().getType())
                    || key == null || !jwt.verify(new MACVerifier(key))) {
                throw new ServiceAssertionRejectedException();
            }

            var claims = jwt.getJWTClaimsSet();
            Instant issuedAt = instant(claims.getIssueTime());
            Instant expiresAt = instant(claims.getExpirationTime());
            Instant now = clock.instant();
            String target = claims.getStringClaim("target");
            String actor = claims.getStringClaim("actor");
            String tokenId = claims.getJWTID();
            if (!expectedIssuer.equals(claims.getIssuer())
                    || !expectedSubject.equals(claims.getSubject())
                    || !Collections.singletonList(expectedAudience).equals(claims.getAudience())
                    || !expectedAudience.equals(target)
                    || (actor != null && !isCanonicalUuid(actor))
                    || issuedAt == null || expiresAt == null || tokenId == null
                    || !TOKEN_ID.matcher(tokenId).matches()
                    || issuedAt.isAfter(now.plus(clockSkew))
                    || !expiresAt.isAfter(now.minus(clockSkew))
                    || !expiresAt.isAfter(issuedAt)
                    || Duration.between(issuedAt, expiresAt).compareTo(maximumLifetime) > 0) {
                throw new ServiceAssertionRejectedException();
            }
            rejectReplay(keyId + ':' + tokenId, expiresAt.plus(clockSkew), now);
            return new AuthenticatedService(claims.getIssuer(), claims.getSubject(), target, tokenId, actor);
        } catch (ServiceAssertionRejectedException rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw new ServiceAssertionRejectedException();
        }
    }

    private synchronized void rejectReplay(String replayKey, Instant retainUntil, Instant now) {
        replayedAssertions.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (replayedAssertions.containsKey(replayKey) || replayedAssertions.size() >= replayCapacity) {
            throw new ServiceAssertionRejectedException();
        }
        replayedAssertions.put(replayKey, retainUntil);
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

    private static Instant instant(java.util.Date value) {
        return value == null ? null : value.toInstant();
    }

    private static String requireIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException();
        return value;
    }

    private static boolean isCanonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException();
        return value;
    }

    public record AuthenticatedService(String issuer, String subject, String target, String assertionId, String actorUserId) {
    }

    public static final class ServiceAssertionRejectedException extends RuntimeException {
    }

    public static final class ServiceAssertionUnavailableException extends RuntimeException {
    }
}
