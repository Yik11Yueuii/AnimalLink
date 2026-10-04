package org.animallink.animal.api;
import org.animallink.animal.application.InternalServiceAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/internal/v1/incident-facts") public class InternalIncidentFactController {
 private final JdbcTemplate jdbc; private final InternalServiceAuthorization auth;
 public InternalIncidentFactController(JdbcTemplate jdbc,InternalServiceAuthorization auth){this.jdbc=jdbc;this.auth=auth;}
 @GetMapping("/animals/{id}") public Map<String,Object> animal(@RequestHeader(name="X-Internal-Service",required=false)String caller,@PathVariable("id") String id){auth.requireIncidentService(caller);return jdbc.queryForList("SELECT id AS animalId,campus_id AS campusId,identity_status AS identityStatus FROM animal WHERE id=?",id).stream().findFirst().orElse(Map.of("exists",false));}
 @PostMapping("/animals/batch") public List<AnimalSummary> animals(@RequestHeader(name="X-Internal-Service",required=false)String caller,@Valid@RequestBody AnimalBatchRequest request){auth.requireIncidentService(caller);List<String> ids=request.animalIds().stream().filter(Objects::nonNull).distinct().toList();if(ids.isEmpty())return List.of();String marks=String.join(",",Collections.nCopies(ids.size(),"?"));return jdbc.queryForList("SELECT id,campus_id,display_name,species,identity_status FROM animal WHERE id IN ("+marks+")",ids.toArray()).stream().map(row->new AnimalSummary(String.valueOf(row.get("id")),String.valueOf(row.get("campus_id")),String.valueOf(row.get("display_name")),String.valueOf(row.get("species")),String.valueOf(row.get("identity_status")))).toList();}
 @GetMapping("/posts/{id}") public Map<String,Object> post(@RequestHeader(name="X-Internal-Service",required=false)String caller,@PathVariable("id") String id){auth.requireIncidentService(caller);return jdbc.queryForList("SELECT id AS postId,campus_id AS campusId,author_user_id AS authorUserId,animal_id AS animalId,status FROM post WHERE id=?",id).stream().findFirst().orElse(Map.of("exists",false));}
 public record AnimalBatchRequest(@NotEmpty @Size(max=100) List<@Size(max=36) String> animalIds){}
 public record AnimalSummary(String id,String campusId,String displayName,String species,String identityStatus){}
}
