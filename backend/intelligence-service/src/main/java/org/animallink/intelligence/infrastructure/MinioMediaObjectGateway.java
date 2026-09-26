package org.animallink.intelligence.infrastructure;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.animallink.intelligence.application.MediaObjectGateway;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MinioMediaObjectGateway implements MediaObjectGateway {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> SUPPORTED = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,511}$");
    private final MinioClient client;
    private final String bucket;

    public MinioMediaObjectGateway(MinioClient client,
                                   @Value("${animallink.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public List<MediaInput> loadAll(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty() || objectKeys.size() > 6) {
            throw new IllegalArgumentException("mediaObjectKeys 必须包含 1 到 6 个对象键");
        }
        List<MediaInput> values = new ArrayList<>(objectKeys.size());
        for (String objectKey : objectKeys) values.add(load(objectKey));
        return List.copyOf(values);
    }

    private MediaInput load(String objectKey) {
        validateKey(objectKey);
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            String contentType = stat.contentType() == null ? "" : stat.contentType().split(";")[0].trim().toLowerCase();
            if (!SUPPORTED.contains(contentType)) {
                throw new UnsupportedMedia("不支持的媒体类型: " + contentType);
            }
            if (stat.size() <= 0 || stat.size() > MAX_BYTES) {
                throw new UnsupportedMedia("媒体文件大小必须在 1 字节到 10 MiB 之间");
            }
            try (InputStream input = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build())) {
                return new MediaInput(objectKey, contentType, input.readAllBytes());
            }
        } catch (UnsupportedMedia exception) {
            throw exception;
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code())
                    || "NoSuchBucket".equals(exception.errorResponse().code())) {
                throw new MediaNotFound("媒体对象不存在");
            }
            throw new DependencyUnavailable("MinIO 暂不可用");
        } catch (Exception exception) {
            throw new DependencyUnavailable("MinIO 暂不可用");
        }
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("..")
                || objectKey.startsWith("/") || objectKey.startsWith("http://")
                || objectKey.startsWith("https://") || !SAFE_KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("媒体必须使用安全的 MinIO objectKey，不能提交公开 URL");
        }
    }
}
