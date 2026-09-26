package org.animallink.intelligence.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.application.MultimodalModelClient;
import org.animallink.intelligence.domain.AnimalObservationDraft;
import org.animallink.intelligence.domain.ObservationSex;
import org.animallink.intelligence.domain.ObservationSpecies;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "animallink.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockMultimodalModelClient implements MultimodalModelClient {
    private final AiProviderProperties properties;
    private final ObjectMapper objectMapper;

    public MockMultimodalModelClient(AiProviderProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public String providerName() { return "mock"; }
    @Override public String modelName() { return properties.getModelName(); }

    @Override
    public ModelResponse analyze(ModelRequest request) {
        boolean unknown = request.text() == null || request.text().isBlank() || request.text().contains("无法判断");
        boolean abnormal = request.text() != null && (request.text().contains("跛") || request.text().contains("活动受限"));
        AnimalObservationDraft draft = new AnimalObservationDraft(
                unknown ? ObservationSpecies.UNKNOWN : ObservationSpecies.CAT,
                ObservationSex.UNKNOWN,
                unknown ? null : "橘白",
                unknown ? List.of() : List.of("尾部有深色环纹"),
                abnormal ? "疑似活动受限" : "未见明显异常",
                unknown ? null : "站立并观察周围",
                1,
                abnormal,
                abnormal ? List.of("疑似活动受限") : List.of(),
                request.locationDescription(),
                request.occurredAt(),
                unknown ? 0.2 : 0.88,
                Map.of("species", unknown ? 0.2 : 0.93, "sex", 0.1),
                List.of("AI 结果仅为可编辑草稿，需由用户确认"),
                unknown ? List.of("species", "sex", "coatColor", "behavior") : List.of("sex"));
        try {
            return new ModelResponse(objectMapper.writeValueAsString(draft));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成本地 mock 结果", exception);
        }
    }
}
