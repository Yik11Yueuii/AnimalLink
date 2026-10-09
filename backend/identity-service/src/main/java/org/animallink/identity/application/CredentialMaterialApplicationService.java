package org.animallink.identity.application;

import org.animallink.identity.domain.CredentialMaterial;
import org.animallink.identity.domain.CredentialMaterialRepository;
import org.animallink.identity.domain.CredentialMaterialStatus;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.UnsupportedCredentialMaterialException;
import org.animallink.identity.domain.UserAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CredentialMaterialApplicationService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int URL_EXPIRY_SECONDS = 600;
    private static final Set<String> SUPPORTED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final CurrentUserProvider currentUserProvider;
    private final CredentialMaterialRepository materials;
    private final CredentialObjectStorage storage;

    public CredentialMaterialApplicationService(CurrentUserProvider currentUserProvider,
                                                CredentialMaterialRepository materials,
                                                CredentialObjectStorage storage) {
        this.currentUserProvider = currentUserProvider;
        this.materials = materials;
        this.storage = storage;
    }

    @Transactional
    public UploadIntent createUploadIntent(String requestedContentType) {
        UserAccount caller = currentUserProvider.requireCurrentUser();
        String contentType = supportedType(requestedContentType);
        String materialId = UUID.randomUUID().toString();
        String objectKey = "credential-materials/" + UUID.randomUUID() + "/" + UUID.randomUUID();
        Instant now = Instant.now();
        materials.insert(new CredentialMaterial(materialId, caller.id(), objectKey, null, null,
                CredentialMaterialStatus.PENDING_UPLOAD, now, null, null));
        CredentialObjectStorage.SignedUrl signed = storage.createUploadUrl(objectKey, contentType, URL_EXPIRY_SECONDS);
        return new UploadIntent(materialId, signed.url(), signed.expiresAt(), List.copyOf(SUPPORTED_TYPES), MAX_BYTES);
    }

    @Transactional(noRollbackFor = UnsupportedCredentialMaterialException.class)
    public CredentialMaterial complete(String materialId) {
        UserAccount caller = currentUserProvider.requireCurrentUser();
        IdRules.requireUuid(materialId, "materialId");
        CredentialMaterial material = owned(materialId, caller.id());
        if (material.status() == CredentialMaterialStatus.READY) return material;
        if (material.status() != CredentialMaterialStatus.PENDING_UPLOAD) {
            throw new org.animallink.identity.domain.ConflictException("凭证材料当前状态不能完成上传");
        }
        CredentialObjectStorage.StoredObject object = storage.read(material.objectKey());
        try {
            validateStoredObject(object);
        } catch (UnsupportedCredentialMaterialException exception) {
            materials.markInvalid(material.id());
            storage.deleteIfExists(material.objectKey());
            throw exception;
        }
        if (!materials.markReady(material.id(), Instant.now(), object.contentType(), object.sizeBytes())) {
            throw new org.animallink.identity.domain.ConflictException("凭证材料状态已变化");
        }
        return materials.findOwnedById(material.id(), caller.id())
                .orElseThrow(() -> new NotFoundException("凭证材料不存在"));
    }

    public ReadAccess readAccess(String materialId) {
        UserAccount caller = currentUserProvider.requireCurrentUser();
        IdRules.requireUuid(materialId, "materialId");
        CredentialMaterial material = materials.findById(materialId)
                .orElseThrow(() -> new NotFoundException("凭证材料不存在"));
        if (!caller.isGovernanceAdmin() && !caller.id().equals(material.ownerUserId())) {
            throw new NotFoundException("凭证材料不存在");
        }
        if (material.status() != CredentialMaterialStatus.READY && material.status() != CredentialMaterialStatus.ATTACHED) {
            throw new org.animallink.identity.domain.ConflictException("凭证材料尚不可读取");
        }
        CredentialObjectStorage.SignedUrl signed = storage.createReadUrl(material.objectKey(), URL_EXPIRY_SECONDS);
        return new ReadAccess(material.id(), material.contentType(), signed.url(), signed.expiresAt());
    }

    public InternalContent readAttached(CredentialMaterial material) {
        if (material.status() != CredentialMaterialStatus.ATTACHED) {
            throw new org.animallink.identity.domain.ConflictException("凭证材料尚未绑定认证申请");
        }
        CredentialObjectStorage.StoredObject object = storage.read(material.objectKey());
        return new InternalContent(object.contentType(), object.content());
    }

    private CredentialMaterial owned(String materialId, String userId) {
        return materials.findOwnedById(materialId, userId)
                .orElseThrow(() -> new NotFoundException("凭证材料不存在"));
    }

    private static String supportedType(String value) {
        String normalized = value == null ? "" : value.split(";")[0].trim().toLowerCase();
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new UnsupportedCredentialMaterialException("仅支持 JPEG、PNG 或 WebP 凭证图片");
        }
        return normalized;
    }

    private static void validateStoredObject(CredentialObjectStorage.StoredObject object) {
        String contentType = supportedType(object.contentType());
        if (object.sizeBytes() <= 0 || object.sizeBytes() > MAX_BYTES || object.content().length != object.sizeBytes()) {
            throw new UnsupportedCredentialMaterialException("凭证材料大小必须在 1 字节到 10 MiB 之间");
        }
        if (!magicMatches(contentType, object.content())) {
            throw new UnsupportedCredentialMaterialException("凭证材料内容与类型不匹配");
        }
    }

    private static boolean magicMatches(String contentType, byte[] content) {
        if ("image/jpeg".equals(contentType)) {
            return content.length >= 3 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff;
        }
        if ("image/png".equals(contentType)) {
            return content.length >= 8 && (content[0] & 0xff) == 0x89 && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47
                    && content[4] == 0x0d && content[5] == 0x0a && content[6] == 0x1a && content[7] == 0x0a;
        }
        return content.length >= 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
    }

    public record UploadIntent(String materialId, String uploadUrl, Instant expiresAt,
                               List<String> allowedContentTypes, int maxSizeBytes) {
    }

    public record ReadAccess(String materialId, String contentType, String readUrl, Instant expiresAt) {
    }

    public record InternalContent(String contentType, byte[] content) {
    }
}
