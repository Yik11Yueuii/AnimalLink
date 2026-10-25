package org.animallink.animal.infrastructure;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class TrustedAdminAssertionVerifier {
    public static final String READ_SCOPE = TrustedAdminRequestPolicy.READ_SCOPE;
    public static final String WRITE_SCOPE = TrustedAdminRequestPolicy.WRITE_SCOPE;
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
    public TrustedAdminAssertionVerifier(
            @Value("${animallink.authentication.animal-admin-assertion.expected-issuer:api-gateway}") String expectedIssuer,
            @Value("${animallink.authentication.animal-admin-assertion.expected-subject:api-gateway}") String expectedSubject,
            @Value("${animallink.authentication.animal-admin-assertion.expected-audience:animal-service}") String expectedAudience,
            @Value("${animallink.authentication.animal-admin-assertion.maximum-lifetime:PT30S}") Duration maximumLifetime,
            @Value("${animallink.authentication.animal-admin-assertion.clock-skew:PT5S}") Duration clockSkew,
            @Value("${animallink.authentication.animal-admin-assertion.replay-capacity:10000}") int replayCapacity,
            @Value("${animallink.authentication.animal-admin-assertion.keys:}") String configuredKeys) {
        this(expectedIssuer, expectedSubject, expectedAudience, maximumLifetime, clockSkew,
                replayCapacity, configuredKeys, Clock.systemUTC());
    }

    TrustedAdminAssertionVerifier(String expectedIssuer, String expectedSubject,
                                  String expectedAudience, Duration maximumLifetime,
                                  Duration clockSkew, int replayCapacity,
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
        } catch (IllegalArgumentException invalid) {
            parsed = Map.of();
            valid = false;
        }
        this.keys = parsed;
        this.configurationValid = valid;
    }

    public AuthenticatedAdmin verify(String authorization, String requestMethod, String requestPath) {
        if (!configurationValid) throw new AssertionUnavailableException();
        String requiredScope = TrustedAdminRequestPolicy.requiredScope(requestMethod, requestPath)
                .orElseThrow(AssertionRejectedException::new);
        if (authorization == null || authorization.length() > 4096
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AssertionRejectedException();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring(7).trim());
            String keyId = jwt.getHeader().getKeyID();
            byte[] key = keyId == null ? null : keys.get(keyId);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !JOSEObjectType.JWT.equals(jwt.getHeader().getType())
                    || key == null || !jwt.verify(new MACVerifier(key))) {
                throw new AssertionRejectedException();
            }
            var claims = jwt.getJWTClaimsSet();
            Instant issuedAt = instant(claims.getIssueTime());
            Instant expiresAt = instant(claims.getExpirationTime());
            Instant now = clock.instant();
            String target = claims.getStringClaim("target");
            String actorId = claims.getStringClaim("actor_id");
            String role = claims.getStringClaim("system_role");
            String assertedMethod = claims.getStringClaim("request_method");
            String assertedPath = claims.getStringClaim("request_path");
            String tokenId = claims.getJWTID();
            if (!expectedIssuer.equals(claims.getIssuer())
                    || !expectedSubject.equals(claims.getSubject())
                    || !Collections.singletonList(expectedAudience).equals(claims.getAudience())
                    || !expectedAudience.equals(target)
                    || issuedAt == null || expiresAt == null || tokenId == null
                    || !TOKEN_ID.matcher(tokenId).matches()
                    || issuedAt.isAfter(now.plus(clockSkew))
                    || !expiresAt.isAfter(now.minus(clockSkew))
                    || !expiresAt.isAfter(issuedAt)
                    || Duration.between(issuedAt, expiresAt).compareTo(maximumLifetime) > 0
                    || !requestMethod.equals(assertedMethod) || !requestPath.equals(assertedPath)
                    || !isUuid(actorId)) {
                throw new AssertionRejectedException();
            }
            if (!"GOVERNANCE_ADMIN".equals(role)
                    || !Collections.singletonList(requiredScope).equals(
                    claims.getStringListClaim("scope"))) {
                throw new AssertionForbiddenException();
            }
            rejectReplay(keyId + ':' + tokenId, expiresAt.plus(clockSkew), now);
            return new AuthenticatedAdmin(actorId, role, requiredScope, tokenId);
        } catch (AssertionForbiddenException | AssertionRejectedException rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw new AssertionRejectedException();
        }
    }

    private synchronized void rejectReplay(String replayKey, Instant retainUntil, Instant now) {
        replayedAssertions.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (replayedAssertions.containsKey(replayKey) || replayedAssertions.size() >= replayCapacity) {
            throw new AssertionRejectedException();
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
            if (!KEY_ID.matcher(keyId).matches() || parsed.containsKey(keyId)) {
                throw new IllegalArgumentException();
            }
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

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException();
        return value;
    }

    private static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record AuthenticatedAdmin(String userId, String systemRole, String scope,
                                     String assertionId) {
    }

    public static final class AssertionRejectedException extends RuntimeException {
    }

    public static final class AssertionForbiddenException extends RuntimeException {
    }

    public static final class AssertionUnavailableException extends RuntimeException {
    }
}
