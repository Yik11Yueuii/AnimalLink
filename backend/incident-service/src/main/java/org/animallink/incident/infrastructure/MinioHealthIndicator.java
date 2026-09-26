package org.animallink.incident.infrastructure;

import io.minio.MinioClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {
    private final MinioClient client;

    public MinioHealthIndicator(MinioClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            client.listBuckets();
            return Health.up().build();
        } catch (Exception ex) {
            return Health.down().withDetail("reason", "MinIO unavailable").build();
        }
    }
}
