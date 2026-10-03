package org.animallink.incident.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CaseParticipantExceptionHandler {
  @ExceptionHandler(EventController.NotFound.class) ResponseEntity<Error> notFound(EventController.NotFound e) { return ResponseEntity.status(404).body(new Error(code(e.getMessage(),"RESOURCE_NOT_FOUND"),"资源不存在")); }
  @ExceptionHandler(EventController.Forbidden.class) ResponseEntity<Error> forbidden(EventController.Forbidden e) { return ResponseEntity.status(403).body(new Error(code(e.getMessage(),"GOVERNANCE_REQUIRED"),"无权执行此操作")); }
  @ExceptionHandler(EventController.Conflict.class) ResponseEntity<Error> conflict(EventController.Conflict e) { return ResponseEntity.status(409).body(new Error(code(e.getMessage(),"STATE_CONFLICT"),"当前资源状态不允许此操作")); }
  @ExceptionHandler(EventController.Dependency.class) ResponseEntity<Error> dependency(EventController.Dependency e) { return ResponseEntity.status(503).body(new Error(code(e.getMessage(),"DEPENDENCY_UNAVAILABLE"),"依赖服务暂不可用")); }
  private String code(String message,String fallback) { for(String value:new String[]{"CASE_OWNER_REQUIRED","ACTIVE_VOLUNTEER_REQUIRED","CASE_NOT_FOUND","CASE_NOT_ACTIVE","CASE_PARTICIPANT_EXISTS","CANNOT_INVITE_CASE_OWNER","CASE_PARTICIPANT_STATE_CONFLICT","CASE_PARTICIPANT_TARGET_REQUIRED","CASE_PARTICIPANT_ACCESS_REQUIRED","CASE_PARTICIPANT_NOT_FOUND","IDENTITY_SERVICE_UNAVAILABLE","EVENT_NOT_ELIGIBLE_FOR_CASE"}) if(message.contains(value)) return value; if(message.contains("Case 不存在"))return "CASE_NOT_FOUND";if(message.contains("Case 已被其他志愿者认领"))return "CASE_ALREADY_CLAIMED";if(message.contains("Event 不存在"))return "EVENT_NOT_FOUND";if(message.contains("已提交"))return "EVENT_DRAFT_FINALIZED";if(message.contains("草稿")&&!message.contains("来源服务"))return "EVENT_DRAFT_NOT_FOUND";if(message.contains("成员"))return "CAMPUS_MEMBERSHIP_REQUIRED";if(message.contains("状态不允许"))return "INVALID_EVENT_TRANSITION";if(message.contains("不接受 Evidence"))return "EVENT_NOT_ACCEPTING_EVIDENCE";if(message.contains("同一 Campus"))return "CROSS_CAMPUS_ANIMAL";if(message.contains("ACTIVE"))return "ANIMAL_NOT_ACTIVE";if(message.contains("来源无效"))return "INVALID_PROVENANCE";if(message.contains("MinIO"))return "MINIO_UNAVAILABLE";if(message.contains("identity-service"))return "IDENTITY_SERVICE_UNAVAILABLE";if(message.contains("animal-service"))return "ANIMAL_SERVICE_UNAVAILABLE";if(message.contains("来源服务"))return "INTELLIGENCE_SERVICE_UNAVAILABLE";return fallback; }
  record Error(String code,String message) {}
}
