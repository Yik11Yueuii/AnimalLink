package org.animallink.identity.domain;

public class VolunteerMembershipException extends RuntimeException {
    private final String code;
    public VolunteerMembershipException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
