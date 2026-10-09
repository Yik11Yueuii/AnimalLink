package org.animallink.identity.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.animallink.identity.application.CredentialMaterialApplicationService;
import org.animallink.identity.domain.CredentialMaterial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/credential-materials")
public class CredentialMaterialController {
    private final CredentialMaterialApplicationService service;

    public CredentialMaterialController(CredentialMaterialApplicationService service) {
        this.service = service;
    }

    @PostMapping("/upload-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadIntentResponse createUploadIntent(@Valid @RequestBody UploadIntentRequest request) {
        return UploadIntentResponse.from(service.createUploadIntent(request.contentType()));
    }

    @PostMapping("/{materialId}/complete")
    public MaterialResponse complete(@PathVariable("materialId") String materialId) {
        return MaterialResponse.from(service.complete(materialId));
    }

    @GetMapping("/{materialId}/read-url")
    public ReadAccessResponse readUrl(@PathVariable("materialId") String materialId) {
        return ReadAccessResponse.from(service.readAccess(materialId));
    }

    public record UploadIntentRequest(@NotBlank @Size(max = 128) String contentType) {
    }

    public record UploadIntentResponse(String materialId, String uploadUrl, Instant expiresAt,
                                       List<String> allowedContentTypes, int maxSizeBytes) {
        static UploadIntentResponse from(CredentialMaterialApplicationService.UploadIntent value) {
            return new UploadIntentResponse(value.materialId(), value.uploadUrl(), value.expiresAt(),
                    value.allowedContentTypes(), value.maxSizeBytes());
        }
    }

    public record MaterialResponse(String id, String contentType, Long sizeBytes, String status,
                                   Instant createdAt, Instant uploadedAt, Instant attachedAt) {
        static MaterialResponse from(CredentialMaterial value) {
            return new MaterialResponse(value.id(), value.contentType(), value.sizeBytes(), value.status().name(),
                    value.createdAt(), value.uploadedAt(), value.attachedAt());
        }
    }

    public record ReadAccessResponse(String materialId, String contentType, String readUrl, Instant expiresAt) {
        static ReadAccessResponse from(CredentialMaterialApplicationService.ReadAccess value) {
            return new ReadAccessResponse(value.materialId(), value.contentType(), value.readUrl(), value.expiresAt());
        }
    }
}
