package org.animallink.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.domain.*;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ObservationFinalizationService {
    private static final Set<String> RETIRABLE_FAILURES = Set.of(
            "SELECTED_ANIMAL_NOT_FOUND", "SELECTED_ANIMAL_ARCHIVED", "CROSS_CAMPUS_ANIMAL");
    private final MatchingRepository matchingRepository;
    private final AiTaskRepository taskRepository;
    private final CampusMembershipAuthorization authorization;
    private final ObservationFinalizationGateway finalizationGateway;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ObservationFinalizationService(MatchingRepository matchingRepository,
                                          AiTaskRepository taskRepository,
                                          CampusMembershipAuthorization authorization,
                                          ObservationFinalizationGateway finalizationGateway,
                                          ObjectMapper objectMapper,
                                          TransactionTemplate transactions) {
        this.matchingRepository = matchingRepository;
        this.taskRepository = taskRepository;
        this.authorization = authorization;
        this.finalizationGateway = finalizationGateway;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    public MatchingDecision finalizeDecision(String matchingRecordId,
                                             FinalizeObservationCommand command) {
        IdentityGateway.CurrentUser user = authorization.requireActiveUser();
        MatchingRecord authorizationRecord = matchingRepository.findById(matchingRecordId)
                .orElseThrow(() -> new NotFound("匹配记录不存在"));
        if (!authorizationRecord.userId().equals(user.id())) {
            throw new NotFound("匹配记录不存在");
        }
        validateRequest(command);
        MatchingDecision completed = matchingRepository.findDecisionByAiTaskId(
                authorizationRecord.aiTaskId()).orElse(null);
        if (completed != null) {
            return requireSameDecision(completed, command);
        }
        // Resolve the remote membership fact before acquiring the matching_record row lock.
        // Animal independently checks membership again before creating a formal Post.
        authorization.requireActiveMember(authorizationRecord.campusId());
        // A prior process may have crashed after Animal durably retired its attempt.
        // Repeat the idempotent retire before removing the current intent locally.
        MatchingDecisionIntent pending = matchingRepository.findIntentByAiTaskId(
                authorizationRecord.aiTaskId()).orElse(null);
        if (pending != null && pending.deterministicFailureCode() != null) {
            recoverRetiredIntent(pending, authorizationRecord);
        }
        Reservation reservation;
        try {
            reservation = transactions.execute(status -> reserve(matchingRecordId, command, user));
        } catch (DuplicateKeyException concurrent) {
            reservation = transactions.execute(status -> reserve(matchingRecordId, command, user));
        }
        if (reservation.completed() != null) {
            return reservation.completed();
        }
        MatchingDecisionIntent intent = reservation.intent();
        ObservationFinalizationGateway.FinalizationResult result = null;
        if (intent.decisionType() != MatchingDecisionType.UNSURE) {
            long version = finalizationGateway.registerAttempt(
                    new ObservationFinalizationGateway.AttemptCommand(intent.userId(),
                            reservation.campusId(), intent.sourceAiTaskId(), intent.matchingRecordId(),
                            intent.id(), intent.decisionType(), intent.selectedAnimalId()));
            if (version < 1) throw new FinalizationServiceUnavailable(
                    "animal-service 返回无效的尝试版本", null);
            transactions.executeWithoutResult(status -> matchingRepository.setIntentVersion(intent.id(), version));
            AiTaskBundle bundle = taskRepository.findBundle(intent.sourceAiTaskId())
                    .orElseThrow(() -> new TaskNoLongerValid("源 AI 任务不存在"));
            AnimalObservationDraft draft = readDraft(bundle.confirmation().confirmedStructuredJson());
            try {
                result = finalizationGateway.finalizeObservation(
                        new ObservationFinalizationGateway.FinalizationCommand(
                                intent.userId(), reservation.campusId(), intent.sourceAiTaskId(),
                                intent.matchingRecordId(), intent.id(), version, intent.decisionType(),
                                intent.selectedAnimalId(), intent.postText(),
                                taskRepository.findMediaObjectKeys(intent.sourceAiTaskId()), draft));
            } catch (AnimalFinalizationRejected rejected) {
                if (intent.decisionType() == MatchingDecisionType.SELECT_EXISTING
                        && (rejected.status() == 404 || rejected.status() == 409)
                        && RETIRABLE_FAILURES.contains(rejected.code())) {
                    transactions.executeWithoutResult(status -> matchingRepository.setIntentFailure(
                            intent.id(), rejected.code()));
                    recoverRetiredIntent(intent.withVersion(version).withFailure(rejected.code()),
                            authorizationRecord);
                }
                throw rejected;
            }
        }
        ObservationFinalizationGateway.FinalizationResult finalResult = result;
        try {
            return transactions.execute(status -> complete(intent, finalResult));
        } catch (DuplicateKeyException concurrent) {
            return transactions.execute(status -> complete(intent, finalResult));
        }
    }

    private Reservation reserve(String matchingRecordId, FinalizeObservationCommand command,
                                IdentityGateway.CurrentUser user) {
        MatchingRecord record = matchingRepository.lockById(matchingRecordId)
                .orElseThrow(() -> new NotFound("匹配记录不存在"));
        if (!record.userId().equals(user.id())) {
            throw new NotFound("匹配记录不存在");
        }
        validateRequest(command);
        MatchingDecision existing = matchingRepository.findDecisionByAiTaskId(record.aiTaskId())
                .orElse(null);
        if (existing != null) {
            return new Reservation(null, record.campusId(), requireSameDecision(existing, command));
        }

        AiTaskBundle bundle = taskRepository.findBundle(record.aiTaskId())
                .orElseThrow(() -> new TaskNoLongerValid("源 AI 任务不存在"));
        if (!bundle.task().userId().equals(user.id())
                || !bundle.task().campusId().equals(record.campusId())
                || bundle.task().status() != AiTaskStatus.SUCCEEDED
                || bundle.task().confirmedAt() == null
                || bundle.confirmation() == null) {
            throw new TaskNoLongerValid("源 AI 任务不再满足正式记录条件");
        }

        MatchingCandidate selected = selectedCandidate(record, command);
        MatchingDecisionIntent intent = matchingRepository.findIntentByAiTaskId(record.aiTaskId())
                .orElse(null);
        if (intent != null) {
            requireSameIntent(intent, record, command);
            return new Reservation(intent, record.campusId(), null);
        }
        intent = new MatchingDecisionIntent(UUID.randomUUID().toString(), record.id(),
                record.aiTaskId(), user.id(), command.decisionType(),
                selected == null ? null : selected.animalId(),
                selected == null ? null : selected.rank(),
                selected == null ? null : selected.finalScore(),
                command.postText(), Instant.now(), null, null);
        matchingRepository.saveIntent(intent);
        return new Reservation(intent, record.campusId(), null);
    }

    private MatchingDecision complete(MatchingDecisionIntent intent,
                                      ObservationFinalizationGateway.FinalizationResult result) {
        MatchingDecisionIntent current = matchingRepository.findIntentByAiTaskId(intent.sourceAiTaskId())
                .orElseThrow(() -> new AlreadyFinalized("决策意图已终结"));
        if (!current.id().equals(intent.id())) throw new AlreadyFinalized("决策意图已变化");
        MatchingDecision existing = matchingRepository.findDecisionByAiTaskId(intent.sourceAiTaskId())
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        MatchingDecision decision = new MatchingDecision(UUID.randomUUID().toString(),
                intent.matchingRecordId(), intent.sourceAiTaskId(), intent.userId(),
                intent.decisionType(), intent.selectedAnimalId(), intent.selectedRank(),
                intent.selectedScore(), result == null ? null : result.postId(),
                result == null ? null : result.proposalId(), Instant.now());
        matchingRepository.saveDecision(decision);
        return decision;
    }

    private void recoverRetiredIntent(MatchingDecisionIntent intent, MatchingRecord record) {
        if (!record.userId().equals(intent.userId())
                || !record.aiTaskId().equals(intent.sourceAiTaskId())
                || intent.fencingVersion() == null
                || !RETIRABLE_FAILURES.contains(intent.deterministicFailureCode())) {
            throw new AlreadyFinalized("决策意图无法安全改选");
        }
        // Animal's RETIRED response is the durable fence. A timeout/5xx/409 keeps
        // the original intent and forbids a different decision.
        finalizationGateway.retireAttempt(new ObservationFinalizationGateway.RetireCommand(
                intent.userId(), record.campusId(), intent.sourceAiTaskId(),
                intent.matchingRecordId(), intent.id(), intent.fencingVersion(),
                intent.decisionType(), intent.selectedAnimalId(), intent.deterministicFailureCode()));
        transactions.executeWithoutResult(status -> {
            MatchingDecisionIntent current = matchingRepository.findIntentByAiTaskId(
                    intent.sourceAiTaskId()).orElse(null);
            if (current != null && current.id().equals(intent.id())
                    && matchingRepository.findDecisionByAiTaskId(intent.sourceAiTaskId()).isEmpty()) {
                matchingRepository.retireIntent(current);
            }
        });
    }

    private void requireSameIntent(MatchingDecisionIntent intent, MatchingRecord record,
                                   FinalizeObservationCommand command) {
        if (!intent.matchingRecordId().equals(record.id())
                || intent.decisionType() != command.decisionType()
                || !Objects.equals(intent.selectedAnimalId(), blankToNull(command.selectedAnimalId()))
                || !Objects.equals(intent.postText(), command.postText())) {
            throw new AlreadyFinalized("同一 AI Task 已保留其他人工决策意图");
        }
    }

    private record Reservation(MatchingDecisionIntent intent, String campusId,
                               MatchingDecision completed) {}

    public MatchingDecision getOwnedDecision(String matchingRecordId) {
        IdentityGateway.CurrentUser user = authorization.requireActiveUser();
        MatchingRecord record = matchingRepository.findById(matchingRecordId)
                .orElseThrow(() -> new NotFound("匹配记录不存在"));
        if (!record.userId().equals(user.id())) {
            throw new NotFound("匹配记录不存在");
        }
        return matchingRepository.findDecisionByRecordId(record.id())
                .orElseThrow(() -> new NotFound("匹配决策不存在"));
    }

    private void validateRequest(FinalizeObservationCommand command) {
        if (command == null || command.decisionType() == null) {
            throw new IllegalArgumentException("decisionType 不能为空");
        }
        if (command.decisionType() == MatchingDecisionType.SELECT_EXISTING) {
            if (command.selectedAnimalId() == null || command.selectedAnimalId().isBlank()) {
                throw new IllegalArgumentException("SELECT_EXISTING 必须提供 selectedAnimalId");
            }
        } else if (command.selectedAnimalId() != null && !command.selectedAnimalId().isBlank()) {
            throw new IllegalArgumentException("NO_MATCH/UNSURE 不能提供 selectedAnimalId");
        }
        if (command.decisionType() != MatchingDecisionType.UNSURE
                && (command.postText() == null || command.postText().isBlank()
                    || command.postText().length() > 2000)) {
            throw new IllegalArgumentException("创建正式 Post 时 postText 必须为 1 到 2000 字符");
        }
        if (command.decisionType() == MatchingDecisionType.UNSURE
                && command.postText() != null) {
            throw new IllegalArgumentException("UNSURE 不接受 postText");
        }
    }

    private MatchingCandidate selectedCandidate(MatchingRecord record,
                                                FinalizeObservationCommand command) {
        if (command.decisionType() != MatchingDecisionType.SELECT_EXISTING) {
            return null;
        }
        return record.candidates().stream()
                .filter(candidate -> candidate.animalId().equals(command.selectedAnimalId()))
                .findFirst()
                .orElseThrow(() -> new SelectedAnimalNotCandidate(
                        "selectedAnimalId 不在该次 MatchingRecord 的候选列表中"));
    }

    private MatchingDecision requireSameDecision(MatchingDecision existing,
                                                 FinalizeObservationCommand command) {
        boolean same = existing.decisionType() == command.decisionType()
                && Objects.equals(existing.selectedAnimalId(), blankToNull(command.selectedAnimalId()));
        if (!same) {
            throw new AlreadyFinalized("该 MatchingRecord 已用不同决策完成");
        }
        return existing;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private AnimalObservationDraft readDraft(String json) {
        try {
            return objectMapper.readValue(json, AnimalObservationDraft.class);
        } catch (JsonProcessingException exception) {
            throw new TaskNoLongerValid("已确认草稿无法读取");
        }
    }
}
