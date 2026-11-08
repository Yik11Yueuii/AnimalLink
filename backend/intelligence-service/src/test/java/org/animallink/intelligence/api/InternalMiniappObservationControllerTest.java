package org.animallink.intelligence.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;import org.junit.jupiter.api.Test;import org.springframework.mock.web.MockHttpServletRequest;
import org.animallink.intelligence.domain.ApiExceptions.*;import org.animallink.intelligence.infrastructure.*;
class InternalMiniappObservationControllerTest {
 @Test void rejectsForgedUserAndOtherUsersMedia(){var request=new MockHttpServletRequest();var controller=new InternalMiniappObservationController(null,request,null,null,"unused");var body=new AiDtos.ParseRequest("00000000-0000-4000-8000-000000000001",List.of("ai-input/other/file"),null,null,null,null,null,null);
 assertThrows(Unauthorized.class,()->controller.parse(body));request.setAttribute(IntelligenceMiniappAssertionFilter.PRINCIPAL,new IntelligenceMiniappAssertionVerifier.AuthenticatedMiniappUser("00000000-0000-4000-8000-000000000001","intelligence.observation.write"));assertThrows(Forbidden.class,()->controller.parse(body));
 }
}
