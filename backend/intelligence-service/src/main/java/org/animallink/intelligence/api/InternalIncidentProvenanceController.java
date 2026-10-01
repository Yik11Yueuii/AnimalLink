package org.animallink.intelligence.api;
import org.animallink.intelligence.domain.*;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/internal/v1/incident-facts") public class InternalIncidentProvenanceController {
 private final AiTaskRepository tasks;private final MatchingRepository matches;public InternalIncidentProvenanceController(AiTaskRepository tasks,MatchingRepository matches){this.tasks=tasks;this.matches=matches;}
 private void trusted(String caller){if(!"incident-service".equals(caller))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);}
 @GetMapping("/ai-tasks/{id}") public Map<String,Object> task(@RequestHeader(name="X-Internal-Service",required=false)String caller,@PathVariable("id") String id){trusted(caller);return tasks.findBundle(id).map(b->Map.<String,Object>of("exists",true,"ownerUserId",b.task().userId(),"campusId",b.task().campusId(),"status",b.task().status().name(),"confirmed",b.confirmation()!=null)).orElse(Map.of("exists",false));}
 @GetMapping("/matches/{id}") public Map<String,Object> match(@RequestHeader(name="X-Internal-Service",required=false)String caller,@PathVariable("id") String id){trusted(caller);return matches.findById(id).map(m->Map.<String,Object>of("exists",true,"ownerUserId",m.userId(),"campusId",m.campusId(),"aiTaskId",m.aiTaskId())).orElse(Map.of("exists",false));}
}
