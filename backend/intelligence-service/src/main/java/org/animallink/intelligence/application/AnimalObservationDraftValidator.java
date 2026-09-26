package org.animallink.intelligence.application;

import jakarta.validation.ValidationException;
import org.animallink.intelligence.domain.AnimalObservationDraft;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

@Component
public class AnimalObservationDraftValidator {
    private static final Set<String> PROHIBITED = Set.of(
            "确诊", "骨折", "感染", "处方", "用药", "服药", "剂量", "治疗方案",
            "diagnosed", "fracture", "prescription", "dosage");

    public void validate(AnimalObservationDraft value) {
        if (value == null || value.species() == null || value.sex() == null) {
            throw new ValidationException("species 和 sex 必须存在，未知时使用 UNKNOWN");
        }
        if (value.estimatedCount() == null || value.estimatedCount() < 1 || value.estimatedCount() > 50) {
            throw new ValidationException("estimatedCount 必须在 1 到 50 之间");
        }
        if (value.possibleAbnormality() == null) {
            throw new ValidationException("possibleAbnormality 必须存在");
        }
        requireCollection(value.distinctiveFeatures(), "distinctiveFeatures", 20);
        requireCollection(value.abnormalFlags(), "abnormalFlags", 20);
        requireCollection(value.warnings(), "warnings", 20);
        requireCollection(value.unknownFields(), "unknownFields", 20);
        if (value.fieldConfidence() == null || value.fieldConfidence().size() > 30) {
            throw new ValidationException("fieldConfidence 必须存在且最多 30 项");
        }
        confidence(value.confidence(), "confidence");
        value.fieldConfidence().forEach((key, confidence) -> {
            if (key == null || key.isBlank() || key.length() > 64) {
                throw new ValidationException("fieldConfidence 字段名无效");
            }
            confidence(confidence, "fieldConfidence." + key);
        });
        if (value.occurredAt() != null && value.occurredAt().isAfter(Instant.now().plusSeconds(300))) {
            throw new ValidationException("occurredAt 不能是未来时间");
        }
        safety(value.visibleCondition());
        safety(value.behavior());
        value.abnormalFlags().forEach(this::safety);
        value.warnings().forEach(this::safety);
        text(value.coatColor(), "coatColor", 120);
        text(value.visibleCondition(), "visibleCondition", 500);
        text(value.behavior(), "behavior", 500);
        text(value.locationDescription(), "locationDescription", 255);
    }

    private void requireCollection(Collection<String> values, String field, int max) {
        if (values == null || values.size() > max || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 255)) {
            throw new ValidationException(field + " 必须是最多 " + max + " 项的非空字符串数组");
        }
    }

    private void confidence(Double value, String field) {
        if (value == null || value < 0 || value > 1 || value.isNaN()) {
            throw new ValidationException(field + " 必须在 0 到 1 之间");
        }
    }

    private void text(String value, String field, int max) {
        if (value != null && value.length() > max) {
            throw new ValidationException(field + " 超出长度限制");
        }
    }

    private void safety(String value) {
        if (value == null) return;
        String normalized = value.toLowerCase(Locale.ROOT);
        if (PROHIBITED.stream().anyMatch(normalized::contains)) {
            throw new ValidationException("结构化结果包含诊断或治疗性断言");
        }
    }
}
