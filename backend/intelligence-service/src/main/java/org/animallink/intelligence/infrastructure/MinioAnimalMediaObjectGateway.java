package org.animallink.intelligence.infrastructure;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.animallink.intelligence.application.AnimalMediaObjectGateway;
import org.animallink.intelligence.application.MediaObjectGateway;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MinioAnimalMediaObjectGateway implements AnimalMediaObjectGateway {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> SUPPORTED = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,511}$");
    private final MinioClient client;
    private final String bucket;

    public MinioAnimalMediaObjectGateway(MinioClient client,
            @Value("${animallink.minio.animal-bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public MediaObjectGateway.MediaInput load(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("..")
                || objectKey.startsWith("/") || !SAFE_KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("Animal 媒体 objectKey 非法");
        }
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            String contentType = stat.contentType() == null ? "" : stat.contentType().split(";")[0].trim().toLowerCase();
            if (!SUPPORTED.contains(contentType) || stat.size() <= 0 || stat.size() > MAX_BYTES) {
                throw new UnsupportedMedia("Animal 候选媒体格式或大小不受支持");
            }
            try (InputStream input = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build())) {
                return new MediaObjectGateway.MediaInput(objectKey, contentType, input.readAllBytes());
            }
        } catch (UnsupportedMedia exception) {
            throw exception;
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code())
                    || "NoSuchBucket".equals(exception.errorResponse().code())) {
                throw new MediaNotFound("Animal 候选媒体不存在");
            }
            throw new DependencyUnavailable("MinIO 暂不可用");
        } catch (Exception exception) {
            throw new DependencyUnavailable("MinIO 暂不可用");
        }
    }
}
