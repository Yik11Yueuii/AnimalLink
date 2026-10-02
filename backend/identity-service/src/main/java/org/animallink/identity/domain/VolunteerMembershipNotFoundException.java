package org.animallink.identity.domain;

public class VolunteerMembershipNotFoundException extends NotFoundException {
    public VolunteerMembershipNotFoundException() { super("VolunteerMembership 不存在"); }
}
