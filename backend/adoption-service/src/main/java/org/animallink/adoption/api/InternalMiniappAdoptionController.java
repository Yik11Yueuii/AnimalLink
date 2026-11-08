package org.animallink.adoption.api;
import jakarta.validation.Valid;import org.animallink.adoption.application.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/internal/v1/miniapp/adoption")
public class InternalMiniappAdoptionController {
 private final AdoptionApplicationService service;private final AdoptionHandoverService handovers;public InternalMiniappAdoptionController(AdoptionApplicationService service,AdoptionHandoverService handovers){this.service=service;this.handovers=handovers;}
 @PostMapping("/listings/{id}/applications") public AdoptionApplicationDtos.ApplicationResponse submit(@PathVariable("id") String id,@Valid @RequestBody AdoptionApplicationDtos.SubmitApplicationRequest input){return AdoptionApplicationDtos.ApplicationResponse.from(new ApplicationView(service.submit(id,input.message()),false));}
 @GetMapping("/applications") public AdoptionApplicationDtos.PageResponse mine(@RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){return AdoptionApplicationDtos.PageResponse.from(service.myApplications(page,size));}
 @GetMapping("/handovers") public java.util.List<AdoptionHandoverDtos.ApplicantResponse> handovers(){return handovers.mine().stream().map(AdoptionHandoverDtos.ApplicantResponse::from).toList();}
 @GetMapping("/applications/{id}") public AdoptionApplicationDtos.ApplicationResponse detail(@PathVariable("id") String id){return AdoptionApplicationDtos.ApplicationResponse.from(service.detail(id));}
 @PostMapping("/applications/{id}/withdraw") public AdoptionApplicationDtos.ApplicationResponse withdraw(@PathVariable("id") String id){return AdoptionApplicationDtos.ApplicationResponse.from(new ApplicationView(service.withdraw(id),false));}
}
