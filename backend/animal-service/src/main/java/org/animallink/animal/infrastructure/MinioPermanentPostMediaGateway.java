package org.animallink.animal.infrastructure;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.animallink.animal.application.PermanentPostMediaGateway;
import org.animallink.animal.domain.MediaCopyException;
import org.animallink.animal.domain.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MinioPermanentPostMediaGateway implements PermanentPostMediaGateway {
    private static final Pattern SAFE_KEY =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,511}$");
    private static final Set<String> SUPPORTED =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final MinioClient client;
    private final String sourceBucket;
    private final String destinationBucket;

    public MinioPermanentPostMediaGateway(
            MinioClient client,
            @Value("${animallink.minio.intelligence-bucket}") String sourceBucket,
            @Value("${animallink.minio.bucket}") String destinationBucket) {
        this.client = client;
        this.sourceBucket = sourceBucket;
        this.destinationBucket = destinationBucket;
    }

    @Override
    public List<PermanentMedia> copyObservationMedia(List<String> sourceObjectKeys,
                                                     String postId) {
        List<PermanentMedia> copied = new ArrayList<>(sourceObjectKeys.size());
        for (int index = 0; index < sourceObjectKeys.size(); index++) {
            String sourceKey = sourceObjectKeys.get(index);
            validateKey(sourceKey);
            try {
                StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                        .bucket(sourceBucket).object(sourceKey).build());
                String contentType = normalizeContentType(stat.contentType());
                if (!SUPPORTED.contains(contentType) || stat.size() <= 0
                        || stat.size() > MAX_BYTES) {
                    throw new IllegalArgumentException(
                            "源媒体必须是 1 字节到 10 MiB 的 JPEG/PNG/WebP 图片");
                }
                String destinationKey = "posts/" + postId + "/"
                        + String.format("%02d", index + 1) + extension(contentType);
                try (InputStream input = client.getObject(GetObjectArgs.builder()
                        .bucket(sourceBucket).object(sourceKey).build())) {
                    client.putObject(PutObjectArgs.builder()
                            .bucket(destinationBucket)
                            .object(destinationKey)
                            .contentType(contentType)
                            .stream(input, stat.size(), -1)
                            .build());
                }
                copied.add(new PermanentMedia(destinationKey, contentType,
                        MediaType.IMAGE, stat.size(), index));
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new MediaCopyException(
                        "观察媒体复制失败，正式 Post 未创建", exception);
            }
        }
        return List.copyOf(copied);
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("..")
                || objectKey.startsWith("/") || objectKey.startsWith("http://")
                || objectKey.startsWith("https://")
                || !SAFE_KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("源媒体 objectKey 不安全");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.split(";")[0].trim().toLowerCase();
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }
}
