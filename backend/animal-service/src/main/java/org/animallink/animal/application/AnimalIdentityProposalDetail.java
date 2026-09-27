package org.animallink.animal.application;

import org.animallink.animal.domain.AnimalIdentityProposal;
import org.animallink.animal.domain.PostMedia;

import java.util.List;

public record AnimalIdentityProposalDetail(
        AnimalIdentityProposal proposal,
        List<PostMedia> media) {
}
