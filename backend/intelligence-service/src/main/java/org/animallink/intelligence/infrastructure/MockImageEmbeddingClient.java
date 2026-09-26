package org.animallink.intelligence.infrastructure;

import org.animallink.intelligence.application.ImageEmbeddingClient;
import org.animallink.intelligence.application.MediaObjectGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "animallink.embedding", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockImageEmbeddingClient implements ImageEmbeddingClient {
    private final EmbeddingProviderProperties properties;

    public MockImageEmbeddingClient(EmbeddingProviderProperties properties) {
        this.properties = properties;
    }

    @Override public String providerName() { return "mock"; }
    @Override public String modelName() { return properties.getModelName(); }
    @Override public String modelVersion() { return properties.getModelVersion(); }

    @Override
    public List<Double> embed(MediaObjectGateway.MediaInput media) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(media.objectKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(media.content());
            byte[] hash = digest.digest();
            List<Double> vector = new ArrayList<>(8);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            for (int index = 0; index < 8; index++) vector.add(buffer.getInt() / (double) Integer.MAX_VALUE);
            return List.copyOf(vector);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
