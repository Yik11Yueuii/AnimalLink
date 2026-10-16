package org.animallink.identity.application;

import org.animallink.identity.domain.UserGovernanceAudit;

import java.util.List;

public record UserGovernanceHistoryPage(
        List<UserGovernanceAudit> items,
        int page,
        int size,
        long total) {
}
