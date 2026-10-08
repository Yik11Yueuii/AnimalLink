package org.animallink.adoption.domain;

public class InternalServiceAccessDeniedException extends RuntimeException {
    public InternalServiceAccessDeniedException(String message) {
        super(message);
    }
}
