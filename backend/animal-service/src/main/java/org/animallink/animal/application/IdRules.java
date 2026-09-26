package org.animallink.animal.application;

import jakarta.validation.ValidationException;

import java.util.UUID;

public final class IdRules {
    private IdRules() {
    }

    public static String requireUuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ValidationException(field + " 必须是合法 UUID");
        }
    }
}
