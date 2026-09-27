package org.animallink.animal.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dev"})
public class LocalAnimalMediaBucketInitializer implements ApplicationRunner {
    private static final Logger log =
            LoggerFactory.getLogger(LocalAnimalMediaBucketInitializer.class);

    private final MinioClient client;
    private final String bucket;

    public LocalAnimalMediaBucketInitializer(
            MinioClient client,
            @Value("${animallink.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 animal-service 本地永久媒体 Bucket: {}", bucket);
            }
        } catch (Exception exception) {
            log.warn("无法创建 animal-service 本地永久媒体 Bucket，正式媒体写入将失败");
        }
    }
}
