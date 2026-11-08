package org.animallink.intelligence.api;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.animallink.intelligence.application.ObservationParsingService;
import org.animallink.intelligence.domain.ApiExceptions.Forbidden;
import org.animallink.intelligence.domain.ApiExceptions.Unauthorized;
import org.animallink.intelligence.infrastructure.IntelligenceMiniappAssertionFilter;
import org.animallink.intelligence.infrastructure.IntelligenceMiniappAssertionVerifier;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/internal/v1/miniapp/observations")
public class InternalMiniappObservationController {
 private final ObservationParsingService service; private final HttpServletRequest request; private final org.animallink.intelligence.application.CampusMembershipAuthorization authorization; private final io.minio.MinioClient minio; private final String bucket;
 public InternalMiniappObservationController(ObservationParsingService service,HttpServletRequest request,org.animallink.intelligence.application.CampusMembershipAuthorization authorization,io.minio.MinioClient minio,@org.springframework.beans.factory.annotation.Value("${animallink.minio.bucket}") String bucket){this.service=service;this.request=request;this.authorization=authorization;this.minio=minio;this.bucket=bucket;}
 @PostMapping("/upload-url") public UploadUrl upload(@Valid @RequestBody UploadInput body){String user=actor("intelligence.observation.write");authorization.requireActiveMember(body.campusId());String key="ai-input/"+user+"/"+java.util.UUID.randomUUID();try{String url=minio.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder().method(io.minio.http.Method.PUT).bucket(bucket).object(key).expiry(600).build());return new UploadUrl(key,url);}catch(Exception e){throw new org.animallink.intelligence.domain.ApiExceptions.DependencyUnavailable("媒体服务不可用");}}
 public record UploadInput(@jakarta.validation.constraints.NotBlank String campusId){} public record UploadUrl(String objectKey,String uploadUrl){}
 @PostMapping("/parse") public AiDtos.TaskResponse parse(@Valid @RequestBody AiDtos.ParseRequest body){owned(body.mediaObjectKeys(),actor("intelligence.observation.write"));return AiDtos.TaskResponse.from(service.parse(body.toCommand()));}
 @PostMapping("/manual") public AiDtos.TaskResponse manual(@Valid @RequestBody AiDtos.ManualObservationRequest body){owned(body.mediaObjectKeys(),actor("intelligence.observation.write"));return AiDtos.TaskResponse.from(service.createManualDraft(body.toCommand()));}
 @GetMapping("/{taskId}") public AiDtos.TaskResponse get(@PathVariable("taskId") String task){actor("intelligence.observation.read");var view=service.getOwned(task);authorization.requireActiveMember(view.task().campusId());return AiDtos.TaskResponse.from(view);}
 @PostMapping("/{taskId}/confirm") public AiDtos.TaskResponse confirm(@PathVariable("taskId") String task,@Valid @RequestBody AiDtos.ConfirmRequest body){actor("intelligence.observation.write");var view=service.getOwned(task);authorization.requireActiveMember(view.task().campusId());return AiDtos.TaskResponse.from(service.confirm(task,body.draft()));}
 private String actor(String scope){Object value=request.getAttribute(IntelligenceMiniappAssertionFilter.PRINCIPAL);if(!(value instanceof IntelligenceMiniappAssertionVerifier.AuthenticatedMiniappUser user)||!scope.equals(user.scope()))throw new Unauthorized("需要可信用户身份");return user.userId();}
 private void owned(List<String> keys,String actor){if(keys==null||keys.stream().anyMatch(k->k==null||!k.startsWith("ai-input/"+actor+"/")||k.contains("..")))throw new Forbidden("媒体不属于当前用户");}
}
