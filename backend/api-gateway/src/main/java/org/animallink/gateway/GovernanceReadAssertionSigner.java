package org.animallink.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
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
public class GovernanceReadAssertionSigner {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final String issuer;
    private final String subject;
    private final Map<String, TargetConfiguration> targets;
    private final Clock clock;

    public GovernanceReadAssertionSigner(
            @Value("${animallink.authentication.incident-admin-assertion.issuer:api-gateway}") String incidentIssuer,
            @Value("${animallink.authentication.incident-admin-assertion.subject:api-gateway}") String incidentSubject,
            @Value("${animallink.authentication.incident-admin-assertion.audience:incident-service}") String incidentAudience,
            @Value("${animallink.authentication.incident-admin-assertion.lifetime:PT15S}") Duration incidentLifetime,
            @Value("${animallink.authentication.incident-admin-assertion.current-key-id:}") String incidentKeyId,
            @Value("${animallink.authentication.incident-admin-assertion.keys:}") String incidentKeys,
            @Value("${animallink.authentication.adoption-admin-assertion.issuer:api-gateway}") String adoptionIssuer,
            @Value("${animallink.authentication.adoption-admin-assertion.subject:api-gateway}") String adoptionSubject,
            @Value("${animallink.authentication.adoption-admin-assertion.audience:adoption-service}") String adoptionAudience,
            @Value("${animallink.authentication.adoption-admin-assertion.lifetime:PT15S}") Duration adoptionLifetime,
            @Value("${animallink.authentication.adoption-admin-assertion.current-key-id:}") String adoptionKeyId,
            @Value("${animallink.authentication.adoption-admin-assertion.keys:}") String adoptionKeys) {
        if (!incidentIssuer.equals(adoptionIssuer) || !incidentSubject.equals(adoptionSubject)) {
            throw new IllegalArgumentException("governance assertion issuer/subject must be consistent");
        }
        this.issuer = requireIdentifier(incidentIssuer);
        this.subject = requireIdentifier(incidentSubject);
        this.targets = Map.of(
                "incident-service", configuration(incidentAudience, incidentLifetime, incidentKeyId, incidentKeys),
                "adoption-service", configuration(adoptionAudience, adoptionLifetime, adoptionKeyId, adoptionKeys));
        this.clock = Clock.systemUTC();
    }

    public String sign(IdentitySessionClient.GatewayAuthenticatedPrincipal principal,
                       AdminGovernanceReadPolicy.Binding binding) {
        TargetConfiguration configuration = targets.get(binding.target());
        if (configuration == null || !configuration.valid()) throw new AssertionUnavailableException();
        String prefix = "incident-service".equals(binding.target()) ? "/incident"
                : "adoption-service".equals(binding.target()) ? "/adoption" : "";
        boolean permittedBinding = !prefix.isEmpty()
                && AdminGovernanceReadPolicy.binding(HttpMethod.GET, prefix + binding.downstreamPath())
                .filter(binding::equals).isPresent();
        if (principal == null || !"GOVERNANCE_ADMIN".equals(principal.systemRole())
                || !isUuid(principal.userId()) || !permittedBinding
                || binding.downstreamPath().length() > 512) {
            throw new AssertionRejectedException();
        }
        try {
            Instant issuedAt = clock.instant();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(issuer).subject(subject).audience(configuration.audience())
                    .claim("target", configuration.audience())
                    .claim("actor_id", principal.userId())
                    .claim("system_role", principal.systemRole())
                    .claim("scope", Collections.singletonList(binding.scope()))
                    .claim("request_method", "GET")
                    .claim("request_path", binding.downstreamPath())
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(configuration.lifetime())))
                    .jwtID(UUID.randomUUID().toString()).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .type(JOSEObjectType.JWT).keyID(configuration.keyId()).build(), claims);
            jwt.sign(new MACSigner(configuration.keys().get(configuration.keyId())));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new AssertionUnavailableException();
        }
    }

    private static TargetConfiguration configuration(String audience, Duration lifetime,
                                                       String keyId, String configuredKeys) {
        try {
            Map<String, byte[]> keys = parseKeys(configuredKeys);
            boolean valid = lifetime != null && !lifetime.isZero() && !lifetime.isNegative()
                    && lifetime.compareTo(Duration.ofSeconds(30)) <= 0
                    && KEY_ID.matcher(keyId == null ? "" : keyId.trim()).matches()
                    && keys.containsKey(keyId.trim());
            return new TargetConfiguration(requireIdentifier(audience), lifetime,
                    keyId == null ? "" : keyId.trim(), keys, valid);
        } catch (RuntimeException invalid) {
            return new TargetConfiguration(audience == null ? "" : audience, Duration.ZERO,
                    "", Map.of(), false);
        }
    }

    private static Map<String, byte[]> parseKeys(String configuredKeys) {
        if (configuredKeys == null || configuredKeys.isBlank()) return Map.of();
        Map<String, byte[]> parsed = new HashMap<>();
        for (String entry : configuredKeys.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) throw new IllegalArgumentException();
            String keyId = entry.substring(0, separator).trim();
            byte[] secret = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            if (!KEY_ID.matcher(keyId).matches() || parsed.putIfAbsent(keyId, secret) != null
                    || secret.length < 32) throw new IllegalArgumentException();
        }
        return Map.copyOf(parsed);
    }

    private static String requireIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException();
        return value;
    }

    private static boolean isUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private record TargetConfiguration(String audience, Duration lifetime, String keyId,
                                       Map<String, byte[]> keys, boolean valid) { }
    static final class AssertionRejectedException extends RuntimeException { }
    static final class AssertionUnavailableException extends RuntimeException { }
}
