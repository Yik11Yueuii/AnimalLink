package org.animallink.identity.api;

import org.animallink.identity.application.CredentialMaterialApplicationService;
import org.animallink.identity.application.IdRules;
import org.animallink.identity.application.InternalServiceAuthorization;
import org.animallink.identity.domain.CampusVerification;
import org.animallink.identity.domain.CampusVerificationRepository;
import org.animallink.identity.domain.CredentialMaterial;
import org.animallink.identity.domain.CredentialMaterialRepository;
import org.animallink.identity.domain.NotFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/campus-verifications")
public class InternalCredentialMaterialController {
    private final InternalServiceAuthorization authorization;
    private final CampusVerificationRepository verifications;
    private final CredentialMaterialRepository materials;
    private final CredentialMaterialApplicationService service;

    public InternalCredentialMaterialController(InternalServiceAuthorization authorization,
                                                CampusVerificationRepository verifications,
                                                CredentialMaterialRepository materials,
                                                CredentialMaterialApplicationService service) {
        this.authorization = authorization;
        this.verifications = verifications;
        this.materials = materials;
        this.service = service;
    }

    @GetMapping("/{verificationId}/credential-material/content")
    public ResponseEntity<byte[]> content(@RequestHeader(name = "X-Internal-Service", required = false) String caller,
                                          @PathVariable("verificationId") String verificationId) {
        authorization.requireIntelligenceService(caller);
        IdRules.requireUuid(verificationId, "verificationId");
        CampusVerification verification = verifications.findVerificationById(verificationId)
                .orElseThrow(() -> new NotFoundException("CampusVerification 不存在"));
        if (verification.materialMediaId() == null) {
            throw new NotFoundException("认证申请未绑定凭证材料");
        }
        CredentialMaterial material = materials.findById(verification.materialMediaId())
                .filter(value -> verification.userId().equals(value.ownerUserId()))
                .orElseThrow(() -> new NotFoundException("凭证材料不存在"));
        var content = service.readAttached(material);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).body(content.content());
    }
}
