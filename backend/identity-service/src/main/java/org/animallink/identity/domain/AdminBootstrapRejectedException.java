package org.animallink.identity.domain;

public class AdminBootstrapRejectedException extends RuntimeException {
    private final String reasonCode;

    public AdminBootstrapRejectedException(String reasonCode) {
        super("Administrator initialization was rejected");
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
