package org.animallink.identity.application;

import org.animallink.identity.domain.MembershipType;

import java.time.LocalDate;

public record SubmitVerificationCommand(
        String campusId,
        MembershipType requestedMembershipType,
        String applicantName,
        String affiliationNote,
        LocalDate expectedGraduationDate,
        String materialMediaId) {
}
