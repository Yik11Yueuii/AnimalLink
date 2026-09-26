package org.animallink.intelligence.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;

@Component
@Profile({"local", "dev"})
public class LocalDemoMediaSeeder implements ApplicationRunner {
    public static final String OBJECT_KEY = "ai-input/demo-observation.png";
    private static final Logger log = LoggerFactory.getLogger(LocalDemoMediaSeeder.class);
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private final MinioClient client;
    private final String bucket;

    public LocalDemoMediaSeeder(MinioClient client,
                                @Value("${animallink.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 Phase 2A 本地私有 Bucket: {}", bucket);
            }
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(OBJECT_KEY).build());
        } catch (Exception missing) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(PNG)) {
                client.putObject(PutObjectArgs.builder().bucket(bucket).object(OBJECT_KEY)
                        .stream(input, PNG.length, -1).contentType("image/png").build());
                log.info("已创建 Phase 2A 本地演示媒体对象: {}", OBJECT_KEY);
            } catch (Exception exception) {
                log.warn("无法创建 Phase 2A 本地演示媒体对象，运行时解析仍会执行对象存在性校验");
            }
        }
    }
}
