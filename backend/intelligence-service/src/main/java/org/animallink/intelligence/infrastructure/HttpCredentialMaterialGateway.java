package org.animallink.intelligence.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.intelligence.application.CredentialMaterialGateway;
import org.animallink.intelligence.application.CredentialMaterialUnavailable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Set;

/** Reads credential bytes only from identity's narrow internal content contract. */
@Component
public class HttpCredentialMaterialGateway implements CredentialMaterialGateway {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");
    private final RestClient client;
    private final HttpServletRequest request;

    public HttpCredentialMaterialGateway(@Qualifier("identityRestClient") RestClient client,
                                         HttpServletRequest request) {
        this.client = client;
        this.request = request;
    }

    @Override
    public CredentialMaterial load(String verificationId) {
        try {
            ResponseEntity<byte[]> response = client.get()
                    .uri("/internal/v1/campus-verifications/{id}/credential-material/content", verificationId)
                    .header("X-Internal-Service", "intelligence-service")
                    .header("X-Trace-Id", traceId())
                    .retrieve().toEntity(byte[].class);
            MediaType contentType = response.getHeaders().getContentType();
            byte[] content = response.getBody();
            if (contentType == null || !SUPPORTED_TYPES.contains(contentType.toString().toLowerCase())) {
                throw new CredentialMaterialUnavailable("UNSUPPORTED_CREDENTIAL_MEDIA",
                        "credential material is not a supported image");
            }
            if (content == null || content.length == 0) {
                throw new CredentialMaterialUnavailable("CREDENTIAL_MATERIAL_UNAVAILABLE",
                        "credential material is empty");
            }
            if (content.length > MAX_BYTES) {
                throw new CredentialMaterialUnavailable("CREDENTIAL_MATERIAL_TOO_LARGE",
                        "credential material exceeds the maximum size");
            }
            return new CredentialMaterial(content, contentType.toString());
        } catch (CredentialMaterialUnavailable exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new CredentialMaterialUnavailable("IDENTITY_CREDENTIAL_GATEWAY_UNAVAILABLE",
                    "identity credential material is unavailable", exception);
        }
    }

    private String traceId() {
        String value = request.getHeader("X-Trace-Id");
        if (value == null || value.isBlank()) value = MDC.get("traceId");
        return value == null ? "intelligence-service" : value;
    }
}
