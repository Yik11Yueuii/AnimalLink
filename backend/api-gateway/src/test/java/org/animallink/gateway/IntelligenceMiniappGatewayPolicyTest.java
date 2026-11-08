package org.animallink.gateway;
import static org.junit.jupiter.api.Assertions.*;import org.junit.jupiter.api.Test;
class IntelligenceMiniappGatewayPolicyTest {
 @Test void onlyAllowsObservationPaths(){String root="/auth/wechat/observations",id="00000000-0000-4000-8000-000000000001";assertTrue(IntelligenceMiniappGatewayPolicy.allowed("POST",root+"/upload-url"));assertTrue(IntelligenceMiniappGatewayPolicy.allowed("POST",root+"/parse"));assertTrue(IntelligenceMiniappGatewayPolicy.allowed("POST",root+"/manual"));assertTrue(IntelligenceMiniappGatewayPolicy.allowed("GET",root+"/"+id));assertTrue(IntelligenceMiniappGatewayPolicy.allowed("POST",root+"/"+id+"/confirm"));assertFalse(IntelligenceMiniappGatewayPolicy.allowed("POST",root+"/"+id+"/match-candidates"));assertFalse(IntelligenceMiniappGatewayPolicy.allowed("GET",root+"/"+id+"/confirm"));}
}
