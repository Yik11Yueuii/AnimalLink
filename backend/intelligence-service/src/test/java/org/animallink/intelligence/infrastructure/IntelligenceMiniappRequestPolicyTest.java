package org.animallink.intelligence.infrastructure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IntelligenceMiniappRequestPolicyTest {
 @Test void onlyExactObservationOperationsGetTheirOwnScopes(){
  String root="/internal/v1/miniapp/observations", id="00000000-0000-4000-8000-000000000001";
  assertEquals("intelligence.observation.write",IntelligenceMiniappRequestPolicy.requiredScope("POST",root+"/parse").orElseThrow());
  assertEquals("intelligence.observation.write",IntelligenceMiniappRequestPolicy.requiredScope("POST",root+"/manual").orElseThrow());
  assertEquals("intelligence.observation.read",IntelligenceMiniappRequestPolicy.requiredScope("GET",root+"/"+id).orElseThrow());
  assertEquals("intelligence.observation.write",IntelligenceMiniappRequestPolicy.requiredScope("POST",root+"/"+id+"/confirm").orElseThrow());
  assertTrue(IntelligenceMiniappRequestPolicy.requiredScope("GET",root+"/"+id+"/confirm").isEmpty());
  assertEquals("intelligence.match.write",IntelligenceMiniappRequestPolicy.requiredScope("POST",root+"/"+id+"/match-candidates").orElseThrow());
  assertEquals("intelligence.match.read",IntelligenceMiniappRequestPolicy.requiredScope("GET",root+"/"+id+"/decision").orElseThrow());
  assertTrue(IntelligenceMiniappRequestPolicy.requiredScope("POST",root+"/"+id+"/finalize").isEmpty());
 }
}
