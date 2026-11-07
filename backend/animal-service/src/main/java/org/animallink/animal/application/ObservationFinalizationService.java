package org.animallink.animal.application;

import org.animallink.animal.domain.*;
import org.animallink.animal.infrastructure.JdbcObservationAttemptGate;
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
    private final JdbcObservationAttemptGate attemptGate;

    public ObservationFinalizationService(ObservationFinalizationRepository repository,
                                          AnimalRepository animalRepository,
                                          CampusMembershipAuthorization membershipAuthorization,
                                          PermanentPostMediaGateway mediaGateway,
                                          TransactionTemplate transactions,
                                          JdbcObservationAttemptGate attemptGate) {
        this.repository = repository;
        this.animalRepository = animalRepository;
        this.membershipAuthorization = membershipAuthorization;
        this.mediaGateway = mediaGateway;
        this.transactions = transactions;
        this.attemptGate = attemptGate;
    }

    public long registerAttempt(String userId, String campusId, String taskId,
                                String matchingRecordId, String attemptId,
                                String decisionType, String selectedAnimalId) {
        IdRules.requireUuid(userId, "userId");
        IdRules.requireUuid(campusId, "campusId");
        IdRules.requireUuid(taskId, "sourceAiTaskId");
        IdRules.requireUuid(attemptId, "attemptId");
        IdRules.requireUuid(matchingRecordId, "sourceMatchingRecordId");
        if ("SELECT_EXISTING".equals(decisionType)) {
            IdRules.requireUuid(selectedAnimalId, "selectedAnimalId");
        } else if (!"NO_MATCH".equals(decisionType) || selectedAnimalId != null) {
            throw new IllegalArgumentException("正式化尝试决策不合法");
        }
        membershipAuthorization.requireActiveMember(userId, campusId);
        try {
            return transactions.execute(status -> attemptGate.register(taskId, attemptId,
                    userId, campusId, matchingRecordId, decisionType, selectedAnimalId).version());
        } catch (DuplicateKeyException concurrentFirstRegistration) {
            return transactions.execute(status -> attemptGate.register(taskId, attemptId,
                    userId, campusId, matchingRecordId, decisionType, selectedAnimalId).version());
        }
    }

    public void retireAttempt(String userId, String taskId, String matchingRecordId,
                              String attemptId, long version,
                              String campusId, String decisionType, String selectedAnimalId,
                              String failureCode) {
        IdRules.requireUuid(taskId, "sourceAiTaskId");
        IdRules.requireUuid(userId, "userId");
        IdRules.requireUuid(matchingRecordId, "sourceMatchingRecordId");
        IdRules.requireUuid(attemptId, "attemptId");
        IdRules.requireUuid(campusId, "campusId");
        IdRules.requireUuid(selectedAnimalId, "selectedAnimalId");
        if (!"SELECT_EXISTING".equals(decisionType) || version < 1
                || !java.util.Set.of("SELECTED_ANIMAL_NOT_FOUND", "SELECTED_ANIMAL_ARCHIVED",
                        "CROSS_CAMPUS_ANIMAL").contains(failureCode)) {
            throw new ObservationAttemptConflictException("只有确定性候选拒绝可撤销尝试");
        }
        transactions.executeWithoutResult(status -> {
            var current = attemptGate.lock(taskId);
            if (current != null && "RETIRED".equals(current.status())
                    && current.attemptId().equals(attemptId) && current.version() == version
                    && current.actorId().equals(userId) && current.campusId().equals(campusId)
                    && current.matchingRecordId().equals(matchingRecordId)
                    && current.decisionType().equals(decisionType)
                    && selectedAnimalId.equals(current.selectedAnimalId())) {
                return; // An identical retirement retry confirms the persisted fence.
            }
            var gate = attemptGate.requireCurrent(taskId, attemptId, version,
                    userId, campusId, matchingRecordId, decisionType, selectedAnimalId);
            if (repository.findFinalizationByAiTaskId(taskId).isPresent()) {
                throw new ObservationAttemptConflictException("正式记录已存在，不可改选");
            }
            Animal candidate = animalRepository.findById(selectedAnimalId).orElse(null);
            String actual = candidate == null ? "SELECTED_ANIMAL_NOT_FOUND"
                    : candidate.identityStatus() != IdentityStatus.ACTIVE ? "SELECTED_ANIMAL_ARCHIVED"
                    : !candidate.campusId().equals(campusId) ? "CROSS_CAMPUS_ANIMAL" : null;
            if (!failureCode.equals(actual)) {
                throw new ObservationAttemptConflictException("无法证明候选仍为确定性拒绝");
            }
            attemptGate.retire(gate, failureCode);
        });
    }

    public ObservationFinalizationResult finalizeObservation(
            ObservationFinalizationCommand command) {
        validate(command);
        String decisionHash = decisionHash(command);
        ObservationFinalization existing = transactions.execute(status -> {
            var gate = attemptGate.requireCurrent(command.sourceAiTaskId(),
                    command.attemptId(), command.fencingVersion(), command.userId(),
                    command.campusId(), command.sourceMatchingRecordId(),
                    command.decisionType(), command.selectedAnimalId());
            return "FINALIZED".equals(gate.status())
                    ? repository.findFinalizationByAiTaskId(command.sourceAiTaskId())
                        .orElseThrow(() -> new ObservationAttemptConflictException("屏障与正式记录不一致"))
                    : null;
        });
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
            return transactions.execute(status -> {
                var gate = attemptGate.requireCurrent(command.sourceAiTaskId(),
                        command.attemptId(), command.fencingVersion(), command.userId(),
                        command.campusId(), command.sourceMatchingRecordId(),
                        command.decisionType(), command.selectedAnimalId());
                if ("FINALIZED".equals(gate.status())) {
                    return sameOrConflict(repository.findFinalizationByAiTaskId(command.sourceAiTaskId())
                            .orElseThrow(() -> new ObservationAttemptConflictException(
                                    "屏障与正式记录不一致")), decisionHash);
                }
                if ("SELECT_EXISTING".equals(command.decisionType())) {
                    // Lock after the gate and recheck before writing. Merge locks Animal rows
                    // but never the attempt gate, so it cannot form a reverse lock order.
                    validateSelectedAnimal(command, animalRepository.findByIdForUpdate(command.selectedAnimalId())
                            .orElseThrow(() -> new SelectedAnimalNotFoundException("selected Animal 不存在")));
                }
                repository.insertAggregate(finalization, post, media, proposal);
                attemptGate.finalizeAttempt(gate, finalization.id());
                return result(finalization);
            });
        } catch (DuplicateKeyException exception) {
            ObservationFinalization concurrent = repository
                    .findFinalizationByAiTaskId(command.sourceAiTaskId())
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
        return validateSelectedAnimal(command, animal);
    }

    private Animal validateSelectedAnimal(ObservationFinalizationCommand command, Animal animal) {
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
        IdRules.requireUuid(command.attemptId(), "attemptId");
        if (command.fencingVersion() < 1) {
            throw new IllegalArgumentException("fencingVersion 必须为正数");
        }
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
