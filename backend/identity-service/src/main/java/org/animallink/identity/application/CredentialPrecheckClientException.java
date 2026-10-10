package org.animallink.identity.application;

public class CredentialPrecheckClientException extends RuntimeException {
    private final String errorCategory;

    public CredentialPrecheckClientException(String errorCategory) {
        this.errorCategory = errorCategory;
    }

    public CredentialPrecheckClientException(String errorCategory, Throwable cause) {
        super(cause);
        this.errorCategory = errorCategory;
    }

    public String errorCategory() {
        return errorCategory;
    }
}
