package org.animallink.animal.application;

import org.animallink.animal.domain.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ObservationFinalizationService {
    private final ObservationFinalizationRepository repository;
    private final AnimalRepository animalRepository;
    private final CampusMembershipAuthorization membershipAuthorization;
    private final PermanentPostMediaGateway mediaGateway;
    private final TransactionTemplate transactions;

    public ObservationFinalizationService(ObservationFinalizationRepository repository,
                                          AnimalRepository animalRepository,
                                          CampusMembershipAuthorization membershipAuthorization,
                                          PermanentPostMediaGateway mediaGateway,
                                          TransactionTemplate transactions) {
        this.repository = repository;
        this.animalRepository = animalRepository;
        this.membershipAuthorization = membershipAuthorization;
        this.mediaGateway = mediaGateway;
        this.transactions = transactions;
    }

    public ObservationFinalizationResult finalizeObservation(
            ObservationFinalizationCommand command) {
        validate(command);
        String decisionHash = decisionHash(command);
        ObservationFinalization existing = repository
                .findFinalization(command.sourceMatchingRecordId()).orElse(null);
        if (existing != null) {
            return sameOrConflict(existing, decisionHash);
        }

        membershipAuthorization.requireActiveMember(command.userId(), command.campusId());
        Animal selected = selectedAnimal(command);
        String postId = deterministicId("post", command.sourceMatchingRecordId());
        String proposalId = "NO_MATCH".equals(command.decisionType())
                ? deterministicId("proposal", command.sourceMatchingRecordId()) : null;
        List<PermanentPostMediaGateway.PermanentMedia> copied =
                mediaGateway.copyObservationMedia(command.mediaObjectKeys(), postId);
        Instant now = Instant.now();
        Post post = Post.observationPost(postId, command.campusId(),
                selected == null ? null : selected.id(), command.userId(), command.postText());
        List<PostMedia> media = copied.stream().map(item -> new PostMedia(
                deterministicId("post-media-" + item.sortOrder(), command.sourceMatchingRecordId()),
                postId, item.objectKey(), item.contentType(), item.mediaType(),
                item.sizeBytes(), item.sortOrder(), Visibility.PUBLIC, now)).toList();
        AnimalIdentityProposal proposal = proposalId == null ? null
                : pendingProposal(proposalId, command, postId, now);
        ObservationFinalization finalization = new ObservationFinalization(
                deterministicId("finalization", command.sourceMatchingRecordId()),
                command.sourceMatchingRecordId(), command.sourceAiTaskId(), command.userId(),
                command.campusId(), command.decisionType(),
                selected == null ? null : selected.id(), postId, proposalId,
                decisionHash, now);
        try {
            transactions.executeWithoutResult(status ->
                    repository.insertAggregate(finalization, post, media, proposal));
            return result(finalization);
        } catch (DuplicateKeyException exception) {
            ObservationFinalization concurrent = repository
                    .findFinalization(command.sourceMatchingRecordId())
                    .orElseThrow(() -> exception);
            return sameOrConflict(concurrent, decisionHash);
        }
    }

    private Animal selectedAnimal(ObservationFinalizationCommand command) {
        if (!"SELECT_EXISTING".equals(command.decisionType())) {
            return null;
        }
        Animal animal = animalRepository.findById(command.selectedAnimalId())
                .orElseThrow(() -> new SelectedAnimalNotFoundException("selected Animal 不存在"));
        if (animal.identityStatus() != IdentityStatus.ACTIVE) {
            throw new SelectedAnimalArchivedException("selected Animal 已归档或不可用");
        }
        if (!animal.campusId().equals(command.campusId())) {
            throw new CrossCampusAnimalException("selected Animal 与观察记录不属于同一 Campus");
        }
        return animal;
    }

    private AnimalIdentityProposal pendingProposal(String proposalId,
                                                   ObservationFinalizationCommand command,
                                                   String postId, Instant now) {
        ObservationFinalizationCommand.ConfirmedDraft draft = command.confirmedDraft();
        String features = draft.distinctiveFeatures() == null
                ? null : String.join("；", draft.distinctiveFeatures());
        return new AnimalIdentityProposal(proposalId, command.campusId(), command.userId(),
                command.sourceAiTaskId(), command.sourceMatchingRecordId(), postId,
                valueOrUnknown(draft.species()), valueOrUnknown(draft.sex()),
                trim(draft.coatColor()), trim(features), trim(command.postText()),
                AnimalIdentityProposalStatus.PENDING_REVIEW, null, null, null, null,
                0, now, now);
    }

    private void validate(ObservationFinalizationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        IdRules.requireUuid(command.userId(), "userId");
        IdRules.requireUuid(command.campusId(), "campusId");
        IdRules.requireUuid(command.sourceAiTaskId(), "sourceAiTaskId");
        IdRules.requireUuid(command.sourceMatchingRecordId(), "sourceMatchingRecordId");
        if (!List.of("SELECT_EXISTING", "NO_MATCH").contains(command.decisionType())) {
            throw new IllegalArgumentException("内部正式记录仅接受 SELECT_EXISTING 或 NO_MATCH");
        }
        if ("SELECT_EXISTING".equals(command.decisionType())) {
            IdRules.requireUuid(command.selectedAnimalId(), "selectedAnimalId");
        } else if (command.selectedAnimalId() != null) {
            throw new IllegalArgumentException("NO_MATCH 不能提供 selectedAnimalId");
        }
        if (command.postText() == null || command.postText().isBlank()
                || command.postText().length() > 2000) {
            throw new IllegalArgumentException("postText 必须为 1 到 2000 个字符");
        }
        if (command.mediaObjectKeys() == null || command.mediaObjectKeys().isEmpty()
                || command.mediaObjectKeys().size() > 6) {
            throw new IllegalArgumentException("正式 Post 必须包含 1 到 6 个源媒体");
        }
        if (command.confirmedDraft() == null) {
            throw new IllegalArgumentException("confirmedDraft 不能为空");
        }
    }

    private ObservationFinalizationResult sameOrConflict(ObservationFinalization existing,
                                                         String decisionHash) {
        if (!Objects.equals(existing.decisionHash(), decisionHash)) {
            throw new DuplicateFinalizeConflictException(
                    "同一 MatchingRecord 已使用不同决策完成正式记录");
        }
        return result(existing);
    }

    private ObservationFinalizationResult result(ObservationFinalization value) {
        return new ObservationFinalizationResult(value.postId(), value.proposalId(),
                value.selectedAnimalId());
    }

    private String decisionHash(ObservationFinalizationCommand command) {
        String value = command.decisionType() + "|" + trim(command.selectedAnimalId());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String deterministicId(String kind, String matchingRecordId) {
        return UUID.nameUUIDFromBytes(
                ("animallink:" + kind + ":" + matchingRecordId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
