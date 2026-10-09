package org.animallink.identity.infrastructure;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.animallink.identity.application.CredentialObjectStorage;
import org.animallink.identity.domain.CredentialStorageException;
import org.animallink.identity.domain.CredentialObjectMissingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MinioCredentialObjectStorage implements CredentialObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioCredentialObjectStorage(MinioClient client,
                                        @Value("${animallink.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public SignedUrl createUploadUrl(String objectKey, String contentType, int expiresSeconds) {
        try {
            return new SignedUrl(client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT).bucket(bucket).object(objectKey).expiry(expiresSeconds).build()),
                    Instant.now().plusSeconds(expiresSeconds));
        } catch (Exception exception) {
            throw unavailable("无法生成凭证上传地址", exception);
        }
    }

    @Override
    public StoredObject read(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
                return new StoredObject(normalize(stat.contentType()), stat.size(), input.readAllBytes());
            }
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code()) || "NoSuchBucket".equals(exception.errorResponse().code())) {
                throw new CredentialObjectMissingException();
            }
            throw unavailable("凭证对象不可用", exception);
        } catch (CredentialStorageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("凭证对象不可用", exception);
        }
    }

    @Override
    public SignedUrl createReadUrl(String objectKey, int expiresSeconds) {
        try {
            return new SignedUrl(client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(objectKey).expiry(expiresSeconds).build()),
                    Instant.now().plusSeconds(expiresSeconds));
        } catch (Exception exception) {
            throw unavailable("无法生成凭证读取地址", exception);
        }
    }

    @Override
    public void deleteIfExists(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception ignored) {
            // Invalid object state in MySQL remains authoritative; deletion is best effort only.
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.split(";")[0].trim().toLowerCase();
    }

    private static CredentialStorageException unavailable(String message, Exception cause) {
        return new CredentialStorageException(message, cause);
    }
}
