package org.animallink.incident.api;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.*;
class IncidentExceptionHandlerTest {
 private final IncidentExceptionHandler h=new IncidentExceptionHandler();
 private IncidentExceptionHandler.Error error(ResponseEntity<?> r){return (IncidentExceptionHandler.Error)r.getBody();}
 @Test void validationIs400(){var r=h.b(new IllegalArgumentException("unsupported image/x"));assertEquals(400,r.getStatusCode().value());assertEquals("INVALID_MEDIA",error(r).code());}
 @Test void authIs401(){var r=h.u(new EventController.Unauthorized("secret"));assertEquals(401,r.getStatusCode().value());assertEquals("UNAUTHORIZED",error(r).code());assertFalse(error(r).message().contains("secret"));}
 @Test void draftNotFoundIs404(){var r=h.n(new EventController.NotFound("草稿不存在"));assertEquals(404,r.getStatusCode().value());assertEquals("EVENT_DRAFT_NOT_FOUND",error(r).code());}
 @Test void conflictIs409(){var r=h.c(new EventController.Conflict("当前 Event 不接受 Evidence"));assertEquals(409,r.getStatusCode().value());assertEquals("EVENT_NOT_ACCEPTING_EVIDENCE",error(r).code());}
 @Test void dependencyIs503(){var r=h.d(new EventController.Dependency("animal-service 暂不可用"));assertEquals(503,r.getStatusCode().value());assertEquals("ANIMAL_SERVICE_UNAVAILABLE",error(r).code());}
 @Test void unexpectedIs500(){var r=h.internal(new RuntimeException("jdbc password"));assertEquals(500,r.getStatusCode().value());assertEquals("INTERNAL_ERROR",error(r).code());assertFalse(error(r).message().contains("jdbc"));}
}