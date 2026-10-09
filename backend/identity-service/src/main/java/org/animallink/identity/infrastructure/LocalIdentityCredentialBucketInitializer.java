package org.animallink.identity.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dev"})
public class LocalIdentityCredentialBucketInitializer implements ApplicationRunner {
    private final MinioClient client;
    private final String bucket;

    public LocalIdentityCredentialBucketInitializer(MinioClient client,
                                                    @Value("${animallink.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception ignored) {
            // Health checks and credential operations expose availability without leaking storage details.
        }
    }
}
