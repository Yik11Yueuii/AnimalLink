package org.animallink.animal.api;
import org.animallink.animal.application.InternalServiceAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/internal/v1/incident-facts") public class InternalIncidentFactController {
 private final JdbcTemplate jdbc; private final InternalServiceAuthorization auth;
 public InternalIncidentFactController(JdbcTemplate jdbc,InternalServiceAuthorization auth){this.jdbc=jdbc;this.auth=auth;}
 @GetMapping("/animals/{id}") public Map<String,Object> animal(@RequestHeader(name="X-Internal-Service",required=false)String caller,@PathVariable("id") String id){auth.requireIncidentService(caller);return jdbc.queryForList("SELECT id AS animalId,campus_id AS campusId,identity_status AS identityStatus FROM animal WHERE id=?",id).stream().findFirst().orElse(Map.of("exists",false));}
 @GetMapping("/posts/{id}") public Map<String,Object> post(@RequestHeader(name="X-Internal-Service",required=false)String caller,@PathVariable("id") String id){auth.requireIncidentService(caller);return jdbc.queryForList("SELECT id AS postId,campus_id AS campusId,author_user_id AS authorUserId,animal_id AS animalId,status FROM post WHERE id=?",id).stream().findFirst().orElse(Map.of("exists",false));}
}
