package org.animallink.adoption.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
public class AdoptionAdminAssertionVerifier {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TOKEN_ID = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private final String issuer, subject, audience;
    private final Duration maximumLifetime, clockSkew;
    private final int replayCapacity;
    private final Map<String, byte[]> keys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> replay = new ConcurrentHashMap<>();

    @Autowired
    public AdoptionAdminAssertionVerifier(
            @Value("${animallink.authentication.adoption-admin-assertion.expected-issuer:api-gateway}") String issuer,
            @Value("${animallink.authentication.adoption-admin-assertion.expected-subject:api-gateway}") String subject,
            @Value("${animallink.authentication.adoption-admin-assertion.expected-audience:adoption-service}") String audience,
            @Value("${animallink.authentication.adoption-admin-assertion.maximum-lifetime:PT30S}") Duration maximumLifetime,
            @Value("${animallink.authentication.adoption-admin-assertion.clock-skew:PT5S}") Duration clockSkew,
            @Value("${animallink.authentication.adoption-admin-assertion.replay-capacity:10000}") int replayCapacity,
            @Value("${animallink.authentication.adoption-admin-assertion.keys:}") String configuredKeys) {
        this(issuer, subject, audience, maximumLifetime, clockSkew, replayCapacity, configuredKeys, Clock.systemUTC());
    }
    AdoptionAdminAssertionVerifier(String issuer, String subject, String audience,
                                   Duration maximumLifetime, Duration clockSkew,
                                   int replayCapacity, String configuredKeys, Clock clock) {
        this.issuer = require(issuer); this.subject = require(subject); this.audience = require(audience);
        this.maximumLifetime = positive(maximumLifetime); this.clockSkew = clockSkew == null || clockSkew.isNegative() ? Duration.ZERO : clockSkew;
        this.replayCapacity = Math.max(1, replayCapacity); this.keys = parseKeys(configuredKeys); this.clock = clock;
    }
    public AuthenticatedAdmin verify(String authorization, String method, String path) {
        if (keys.isEmpty()) throw new Unavailable();
        String scope = AdoptionAdminRequestPolicy.requiredScope(method, path).orElseThrow(Rejected::new);
        if (authorization == null || authorization.length() > 4096 || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw new Rejected();
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring(7).trim()); String kid = jwt.getHeader().getKeyID(); byte[] key = kid == null ? null : keys.get(kid);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !JOSEObjectType.JWT.equals(jwt.getHeader().getType()) || key == null || !jwt.verify(new MACVerifier(key))) throw new Rejected();
            var claims = jwt.getJWTClaimsSet(); Instant now = clock.instant();
            Instant issued = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant(); Instant expires = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
            String actor = claims.getStringClaim("actor_id"), tokenId = claims.getJWTID();
            if (!issuer.equals(claims.getIssuer()) || !subject.equals(claims.getSubject()) || !Collections.singletonList(audience).equals(claims.getAudience())
                    || !audience.equals(claims.getStringClaim("target")) || issued == null || expires == null || tokenId == null || !TOKEN_ID.matcher(tokenId).matches()
                    || issued.isAfter(now.plus(clockSkew)) || !expires.isAfter(now.minus(clockSkew)) || !expires.isAfter(issued)
                    || Duration.between(issued, expires).compareTo(maximumLifetime) > 0 || !method.equals(claims.getStringClaim("request_method"))
                    || !path.equals(claims.getStringClaim("request_path")) || !isUuid(actor)) throw new Rejected();
            if (!"GOVERNANCE_ADMIN".equals(claims.getStringClaim("system_role")) || !Collections.singletonList(scope).equals(claims.getStringListClaim("scope"))) throw new Forbidden();
            rejectReplay(kid + ':' + tokenId, expires.plus(clockSkew), now); return new AuthenticatedAdmin(actor, scope);
        } catch (Forbidden | Rejected rejected) { throw rejected; } catch (Exception invalid) { throw new Rejected(); }
    }
    private synchronized void rejectReplay(String id, Instant retainUntil, Instant now) { replay.entrySet().removeIf(entry -> !entry.getValue().isAfter(now)); if (replay.containsKey(id) || replay.size() >= replayCapacity) throw new Rejected(); replay.put(id, retainUntil); }
    private static Map<String, byte[]> parseKeys(String value) {
        if (value == null || value.isBlank()) return Map.of(); Map<String, byte[]> result = new HashMap<>();
        for (String entry : value.split(";")) { int at = entry.indexOf('='); if (at <= 0 || at == entry.length() - 1) return Map.of(); String kid = entry.substring(0, at).trim();
            try { byte[] secret = Base64.getDecoder().decode(entry.substring(at + 1).trim()); if (!KEY_ID.matcher(kid).matches() || secret.length < 32 || result.putIfAbsent(kid, secret) != null) return Map.of(); } catch (IllegalArgumentException invalid) { return Map.of(); } }
        return Map.copyOf(result);
    }
    private static String require(String value) { if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException(); return value; }
    private static Duration positive(Duration value) { if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(); return value; }
    private static boolean isUuid(String value) { try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); } catch (IllegalArgumentException e) { return false; } }
    public record AuthenticatedAdmin(String userId, String scope) { }
    public static final class Rejected extends RuntimeException { } public static final class Forbidden extends RuntimeException { } public static final class Unavailable extends RuntimeException { }
}
