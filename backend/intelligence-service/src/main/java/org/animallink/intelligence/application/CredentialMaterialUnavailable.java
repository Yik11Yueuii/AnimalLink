package org.animallink.intelligence.application;

public class CredentialMaterialUnavailable extends RuntimeException {
    private final String category;

    public CredentialMaterialUnavailable(String category, String message) {
        super(message);
        this.category = category;
    }

    public CredentialMaterialUnavailable(String category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
