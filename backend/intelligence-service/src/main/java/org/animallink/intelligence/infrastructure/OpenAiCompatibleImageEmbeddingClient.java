package org.animallink.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import org.animallink.intelligence.application.ImageEmbeddingClient;
import org.animallink.intelligence.application.MediaObjectGateway;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "animallink.embedding", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleImageEmbeddingClient implements ImageEmbeddingClient {
    private final EmbeddingProviderProperties properties;
    private final RestClient client;

    public OpenAiCompatibleImageEmbeddingClient(EmbeddingProviderProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.client = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    @Override public String providerName() { return "openai-compatible"; }
    @Override public String modelName() { return properties.getModelName(); }
    @Override public String modelVersion() { return properties.getModelVersion(); }

    @Override
    public List<Double> embed(MediaObjectGateway.MediaInput media) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new EmbeddingUnavailable("未配置图像 Embedding Provider API Key");
        }
        String dataUrl = "data:" + media.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(media.content());
        try {
            JsonNode response = client.post().uri("/embeddings")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(Map.of("model", properties.getModelName(), "input", dataUrl))
                    .retrieve().body(JsonNode.class);
            JsonNode values = response == null ? null : response.at("/data/0/embedding");
            if (values == null || !values.isArray() || values.isEmpty()) {
                throw new InvalidEmbeddingVector("Embedding Provider 未返回有效向量");
            }
            List<Double> vector = new ArrayList<>();
            values.forEach(value -> vector.add(value.asDouble(Double.NaN)));
            return List.copyOf(vector);
        } catch (InvalidEmbeddingVector exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) throw new EmbeddingTimeout("Embedding Provider 响应超时", exception);
            throw new EmbeddingUnavailable("无法连接 Embedding Provider", exception);
        } catch (RestClientResponseException exception) {
            throw new EmbeddingUnavailable("Embedding Provider 返回错误状态: " + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new EmbeddingUnavailable("Embedding Provider 调用失败", exception);
        }
    }

    private boolean hasTimeoutCause(Throwable value) {
        Throwable current = value;
        while (current != null) {
            if (current instanceof SocketTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }
}
