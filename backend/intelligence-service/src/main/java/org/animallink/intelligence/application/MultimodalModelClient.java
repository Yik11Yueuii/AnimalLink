package org.animallink.intelligence.application;

import java.time.Instant;
import java.util.List;

public interface MultimodalModelClient {
    String providerName();
    String modelName();
    ModelResponse analyze(ModelRequest request);

    record ModelRequest(String prompt, String text, String locationDescription, Instant occurredAt,
                        List<MediaObjectGateway.MediaInput> media) {}
    record ModelResponse(String rawResponse) {}
}
