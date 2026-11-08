package org.animallink.animal.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import com.nimbusds.jose.*;import com.nimbusds.jose.crypto.MACSigner;import com.nimbusds.jwt.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
class AnimalMiniappAssertionVerifierTest {
 @Test void verifiesActorPathScopeAndReplay() throws Exception {
  byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);String keys="test="+Base64.getEncoder().encodeToString(key),actor="00000000-0000-4000-8000-000000000001",path="/internal/v1/miniapp/community/posts";Instant now=Instant.now();
  var v=new AnimalMiniappAssertionVerifier("api-gateway","api-gateway","animal-service",Duration.ofSeconds(30),Duration.ofSeconds(5),100,keys,Clock.systemUTC());
  var claims=new JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway").audience("animal-service").claim("target","animal-service").claim("actor_id",actor).claim("system_role","USER").claim("scope",List.of("animal.community.write")).claim("request_method","POST").claim("request_path",path).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(15))).jwtID(UUID.randomUUID().toString()).build();
  var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID("test").build(),claims);jwt.sign(new MACSigner(key));String token="Bearer "+jwt.serialize();
  assertThrows(AnimalMiniappAssertionVerifier.Rejected.class,()->v.verify(token,"POST","/internal/v1/miniapp/community/posts/00000000-0000-4000-8000-000000000001/comments"));
  assertEquals(actor,v.verify(token,"POST",path).userId());assertThrows(AnimalMiniappAssertionVerifier.Rejected.class,()->v.verify(token,"POST",path));
 }
}
