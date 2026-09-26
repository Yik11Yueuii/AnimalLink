package org.animallink.identity.application;

import jakarta.validation.ValidationException;

import java.util.UUID;

public final class IdRules {
    private IdRules() {
    }

    public static String requireUuid(String value, String fieldName) {
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ValidationException(fieldName + " 必须是 UUID 格式");
        }
    }

    public static String optionalUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireUuid(value, fieldName);
    }
}
