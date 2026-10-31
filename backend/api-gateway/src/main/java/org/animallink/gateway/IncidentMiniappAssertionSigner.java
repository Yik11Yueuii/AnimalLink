package org.animallink.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate keyring and claim set for Gateway-verified ordinary-user rescue draft writes. */
@Component
public class IncidentMiniappAssertionSigner {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final String issuer, subject, audience, keyId; private final Duration lifetime; private final Map<String, byte[]> keys; private final Clock clock;
    public IncidentMiniappAssertionSigner(@Value("${animallink.authentication.incident-miniapp-assertion.issuer:api-gateway}") String issuer,
            @Value("${animallink.authentication.incident-miniapp-assertion.subject:api-gateway}") String subject,
            @Value("${animallink.authentication.incident-miniapp-assertion.audience:incident-service}") String audience,
            @Value("${animallink.authentication.incident-miniapp-assertion.lifetime:PT15S}") Duration lifetime,
            @Value("${animallink.authentication.incident-miniapp-assertion.current-key-id:}") String keyId,
            @Value("${animallink.authentication.incident-miniapp-assertion.keys:}") String configuredKeys) { this(issuer,subject,audience,lifetime,keyId,configuredKeys,Clock.systemUTC()); }
    IncidentMiniappAssertionSigner(String issuer,String subject,String audience,Duration lifetime,String keyId,String configuredKeys,Clock clock) {
        this.issuer=required(issuer);this.subject=required(subject);this.audience=required(audience);this.lifetime=lifetime;this.keyId=keyId==null?"":keyId.trim();this.keys=parse(configuredKeys);this.clock=clock;
    }
    public String sign(String actor, String method, String path) {
        if (!uuid(actor) || !"USER".equals("USER") || !IncidentMiniappGatewayPolicy.allowed(method,path) || lifetime==null||lifetime.isNegative()||lifetime.isZero()||lifetime.compareTo(Duration.ofSeconds(30))>0||!KEY_ID.matcher(keyId).matches()||!keys.containsKey(keyId)) throw new Unavailable();
        try { Instant now=clock.instant(); var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience).claim("target",audience).claim("actor_id",actor).claim("system_role","USER").claim("scope", Collections.singletonList(IncidentMiniappGatewayPolicy.SCOPE)).claim("request_method",method).claim("request_path",IncidentMiniappGatewayPolicy.downstreamPath(path)).issueTime(Date.from(now)).expirationTime(Date.from(now.plus(lifetime))).jwtID(UUID.randomUUID().toString()).build(); var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID(keyId).build(),claims);token.sign(new MACSigner(keys.get(keyId)));return token.serialize(); } catch(Exception e){throw new Unavailable();}
    }
    private static Map<String,byte[]> parse(String value){if(value==null||value.isBlank())return Map.of();Map<String,byte[]> r=new HashMap<>();for(String entry:value.split(";")){int at=entry.indexOf('=');if(at<=0||at==entry.length()-1)return Map.of();try{String k=entry.substring(0,at).trim();byte[] v=Base64.getDecoder().decode(entry.substring(at+1).trim());if(!KEY_ID.matcher(k).matches()||v.length<32||r.putIfAbsent(k,v)!=null)return Map.of();}catch(IllegalArgumentException e){return Map.of();}}return Map.copyOf(r);}
    private static String required(String value){if(value==null||value.isBlank()||value.length()>128)throw new IllegalArgumentException();return value;}
    private static boolean uuid(String value){try{return value!=null&&UUID.fromString(value).toString().equalsIgnoreCase(value);}catch(IllegalArgumentException e){return false;}}
    public static final class Unavailable extends RuntimeException { }
}
