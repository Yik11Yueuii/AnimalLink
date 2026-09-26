package org.animallink.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import org.animallink.intelligence.application.MediaObjectGateway;
import org.animallink.intelligence.application.MultimodalModelClient;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "animallink.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleMultimodalModelClient implements MultimodalModelClient {
    private final AiProviderProperties properties;
    private final RestClient client;

    public OpenAiCompatibleMultimodalModelClient(AiProviderProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.client = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    @Override public String providerName() { return "openai-compatible"; }
    @Override public String modelName() { return properties.getModelName(); }

    @Override
    public ModelResponse analyze(ModelRequest request) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ProviderUnavailable("未配置模型服务 API Key");
        }
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userText(request)));
        for (MediaObjectGateway.MediaInput media : request.media()) {
            String dataUrl = "data:" + media.contentType() + ";base64,"
                    + Base64.getEncoder().encodeToString(media.content());
            userContent.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModelName());
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.prompt()),
                Map.of("role", "user", "content", userContent)));
        try {
            JsonNode response = client.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(body).retrieve().body(JsonNode.class);
            JsonNode content = response == null ? null : response.at("/choices/0/message/content");
            if (content == null || !content.isTextual()) {
                throw new InvalidModelResponse("模型响应缺少 message.content");
            }
            return new ModelResponse(content.asText());
        } catch (InvalidModelResponse exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) throw new ProviderTimeout("模型服务响应超时", exception);
            throw new ProviderUnavailable("无法连接模型服务", exception);
        } catch (RestClientResponseException exception) {
            throw new ProviderUnavailable("模型服务返回错误状态: " + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            if (hasTimeoutCause(exception)) throw new ProviderTimeout("模型服务响应超时", exception);
            throw new ProviderUnavailable("模型服务调用失败", exception);
        }
    }

    private String userText(ModelRequest request) {
        return "用户描述: " + nullable(request.text()) + "\n位置描述: "
                + nullable(request.locationDescription()) + "\n发生时间: " + nullable(request.occurredAt());
    }

    private String nullable(Object value) { return value == null ? "未提供" : value.toString(); }

    private boolean hasTimeoutCause(Throwable value) {
        Throwable current = value;
        while (current != null) {
            String type = current.getClass().getSimpleName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (current instanceof SocketTimeoutException || type.contains("timeout")
                    || message.contains("timed out") || message.contains("timeout")) return true;
            current = current.getCause();
        }
        return false;
    }
}
