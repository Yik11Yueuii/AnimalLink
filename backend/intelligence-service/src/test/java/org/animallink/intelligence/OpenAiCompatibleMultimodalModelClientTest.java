package org.animallink.intelligence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.animallink.intelligence.application.MediaObjectGateway;
import org.animallink.intelligence.application.MultimodalModelClient;
import org.animallink.intelligence.domain.ApiExceptions.ProviderTimeout;
import org.animallink.intelligence.domain.ApiExceptions.ProviderUnavailable;
import org.animallink.intelligence.infrastructure.AiProviderProperties;
import org.animallink.intelligence.infrastructure.OpenAiCompatibleMultimodalModelClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiCompatibleMultimodalModelClientTest {
    private static final String SECRET = "phase2a-super-secret-test-key";
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void extractsStrictContentThroughReplaceableClient() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"species\\\":\\\"UNKNOWN\\\"}\"}}]}"));
        MultimodalModelClient.ModelResponse response = client(1000).analyze(request());
        assertThat(response.rawResponse()).isEqualTo("{\"species\":\"UNKNOWN\"}");
    }

    @Test
    void mapsProvider500WithoutLeakingApiKey(CapturedOutput output) throws Exception {
        start(exchange -> respond(exchange, 500, "upstream failed"));
        assertThatThrownBy(() -> client(1000).analyze(request()))
                .isInstanceOf(ProviderUnavailable.class);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void mapsReadTimeoutToGatewayTimeoutFailure() throws Exception {
        start(exchange -> {
            try { Thread.sleep(200); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "{\"choices\":[]}");
        });
        assertThatThrownBy(() -> client(30).analyze(request()))
                .isInstanceOf(ProviderTimeout.class);
    }

    private OpenAiCompatibleMultimodalModelClient client(int readTimeoutMs) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey(SECRET);
        properties.setModelName("test-vision-model");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(readTimeoutMs);
        return new OpenAiCompatibleMultimodalModelClient(properties);
    }

    private MultimodalModelClient.ModelRequest request() {
        return new MultimodalModelClient.ModelRequest("strict prompt", "一只动物", null, null,
                List.of(new MediaObjectGateway.MediaInput("test.png", "image/png", new byte[]{1, 2, 3})));
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> handler.handle(exchange));
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
