package org.animallink.animal.application;

import org.animallink.animal.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnimalIdentityProposalService {
    private final ObservationFinalizationRepository proposalRepository;
    private final AnimalRepository animalRepository;
    private final GovernanceAuthorization governanceAuthorization;
    private final IdentityGateway identityGateway;
    private final TransactionTemplate transactions;

    public AnimalIdentityProposalService(
            ObservationFinalizationRepository proposalRepository,
            AnimalRepository animalRepository,
            GovernanceAuthorization governanceAuthorization,
            IdentityGateway identityGateway,
            TransactionTemplate transactions) {
        this.proposalRepository = proposalRepository;
        this.animalRepository = animalRepository;
        this.governanceAuthorization = governanceAuthorization;
        this.identityGateway = identityGateway;
        this.transactions = transactions;
    }

    public PageResult<AnimalIdentityProposal> list(AnimalIdentityProposalStatus status,
                                                   int page, int size) {
        governanceAuthorization.requireAdministrator();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return new PageResult<>(proposalRepository.findProposals(status, safeSize,
                safePage * safeSize), safePage, safeSize,
                proposalRepository.countProposals(status));
    }

    public AnimalIdentityProposalDetail detail(String proposalId) {
        governanceAuthorization.requireAdministrator();
        return proposalRepository.findProposalDetail(IdRules.requireUuid(proposalId, "proposalId"));
    }

    public AnimalIdentityProposalDetail approveCreate(String proposalId,
                                                      ApproveProposalCommand command) {
        IdentityGateway.CurrentUser admin = governanceAuthorization.requireAdministrator();
        String validId = IdRules.requireUuid(proposalId, "proposalId");
        AnimalIdentityProposal current = proposalRepository.findProposal(validId)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal 不存在"));
        ensurePending(current);
        identityGateway.requireActiveCampus(current.campusId());
        if (command == null || command.species() == null) {
            throw new IllegalArgumentException("approve-create 必须明确 species");
        }
        return transactions.execute(status -> {
            AnimalIdentityProposal proposal = lockPending(validId);
            Animal animal = Animal.create(proposal.campusId(), command.displayName(),
                    command.species(), first(command.sex(), parseSex(proposal.proposedSex())),
                    first(command.coatColor(), proposal.proposedCoatColor()),
                    first(command.distinctiveFeatures(),
                            proposal.proposedDistinctiveFeatures()),
                    first(command.description(), proposal.proposedDescription()),
                    SterilizationStatus.UNKNOWN, command.typicalArea());
            animalRepository.insert(animal);
            proposalRepository.updateProposal(proposal.approve(admin.id(), animal.id()));
            proposalRepository.bindPostToAnimal(proposal.postId(), animal.id());
            return proposalRepository.findProposalDetail(validId);
        });
    }

    public AnimalIdentityProposalDetail linkExisting(String proposalId, String animalId,
                                                     String reviewReason) {
        IdentityGateway.CurrentUser admin = governanceAuthorization.requireAdministrator();
        String validProposalId = IdRules.requireUuid(proposalId, "proposalId");
        String validAnimalId = IdRules.requireUuid(animalId, "animalId");
        return transactions.execute(status -> {
            AnimalIdentityProposal proposal = lockPending(validProposalId);
            Animal animal = animalRepository.findById(validAnimalId)
                    .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在"));
            if (animal.identityStatus() != IdentityStatus.ACTIVE) {
                throw new SelectedAnimalArchivedException("Animal 已归档或不可用");
            }
            if (!animal.campusId().equals(proposal.campusId())) {
                throw new CrossCampusAnimalException(
                        "Animal 与 Proposal 不属于同一 Campus");
            }
            proposalRepository.updateProposal(
                    proposal.linkExisting(admin.id(), animal.id(), reviewReason));
            proposalRepository.bindPostToAnimal(proposal.postId(), animal.id());
            return proposalRepository.findProposalDetail(validProposalId);
        });
    }

    public AnimalIdentityProposalDetail reject(String proposalId, String reason) {
        IdentityGateway.CurrentUser admin = governanceAuthorization.requireAdministrator();
        String validId = IdRules.requireUuid(proposalId, "proposalId");
        return transactions.execute(status -> {
            AnimalIdentityProposal proposal = lockPending(validId);
            proposalRepository.updateProposal(proposal.reject(admin.id(), reason));
            return proposalRepository.findProposalDetail(validId);
        });
    }

    private AnimalIdentityProposal lockPending(String proposalId) {
        AnimalIdentityProposal proposal = proposalRepository.lockProposal(proposalId)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal 不存在"));
        ensurePending(proposal);
        return proposal;
    }

    private void ensurePending(AnimalIdentityProposal proposal) {
        if (proposal.status() != AnimalIdentityProposalStatus.PENDING_REVIEW) {
            throw new ProposalAlreadyReviewedException("Proposal 已完成审核");
        }
    }

    private AnimalSex parseSex(String value) {
        try {
            return value == null ? AnimalSex.UNKNOWN : AnimalSex.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return AnimalSex.UNKNOWN;
        }
    }

    private <T> T first(T preferred, T fallback) {
        if (preferred instanceof String text) {
            return text.isBlank() ? fallback : preferred;
        }
        return preferred == null ? fallback : preferred;
    }
}
