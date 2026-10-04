package org.animallink.adoption.domain;

public class ApplicationNotApprovedException extends RuntimeException {
    public ApplicationNotApprovedException(String message) { super(message); }
}
