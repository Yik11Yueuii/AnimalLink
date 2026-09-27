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
import java.util.UUID;

@Service
public class ObservationFinalizationService {
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
        return transactions.execute(status ->
                finalizeLocked(matchingRecordId, command, user));
    }

    private MatchingDecision finalizeLocked(String matchingRecordId,
                                            FinalizeObservationCommand command,
                                            IdentityGateway.CurrentUser user) {
        MatchingRecord record = matchingRepository.lockById(matchingRecordId)
                .orElseThrow(() -> new NotFound("匹配记录不存在"));
        if (!record.userId().equals(user.id())) {
            throw new NotFound("匹配记录不存在");
        }
        validateRequest(command);
        MatchingDecision existing = matchingRepository.findDecisionByRecordId(record.id())
                .orElse(null);
        if (existing != null) {
            return requireSameDecision(existing, command);
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
        authorization.requireActiveMember(record.campusId());

        MatchingCandidate selected = selectedCandidate(record, command);
        ObservationFinalizationGateway.FinalizationResult result = null;
        if (command.decisionType() != MatchingDecisionType.UNSURE) {
            AnimalObservationDraft draft = readDraft(bundle.confirmation().confirmedStructuredJson());
            result = finalizationGateway.finalizeObservation(
                    new ObservationFinalizationGateway.FinalizationCommand(
                            user.id(), record.campusId(), record.aiTaskId(), record.id(),
                            command.decisionType(), command.selectedAnimalId(), command.postText(),
                            taskRepository.findMediaObjectKeys(record.aiTaskId()), draft));
        }
        MatchingDecision decision = new MatchingDecision(UUID.randomUUID().toString(), record.id(),
                user.id(), command.decisionType(),
                selected == null ? null : selected.animalId(),
                selected == null ? null : selected.rank(),
                selected == null ? null : selected.finalScore(),
                result == null ? null : result.postId(),
                result == null ? null : result.proposalId(), Instant.now());
        try {
            matchingRepository.saveDecision(decision);
            return decision;
        } catch (DuplicateKeyException exception) {
            MatchingDecision concurrent = matchingRepository.findDecisionByRecordId(record.id())
                    .orElseThrow(() -> exception);
            return requireSameDecision(concurrent, command);
        }
    }

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
                && (command.postText() == null || command.postText().isBlank())) {
            throw new IllegalArgumentException("创建正式 Post 时 postText 不能为空");
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
