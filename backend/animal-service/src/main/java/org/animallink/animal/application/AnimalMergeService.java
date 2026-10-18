package org.animallink.animal.application;

import jakarta.validation.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.animallink.animal.domain.AdoptionStatus;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalMergeRecord;
import org.animallink.animal.domain.AnimalMergeRepository;
import org.animallink.animal.domain.CurrentContext;
import org.animallink.animal.domain.IdentityStatus;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.domain.StateConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnimalMergeService {
    private static final int MAX_REASON_CODE_POINTS = 1000;
    private static final int MAX_PAGE_SIZE = 100;

    private final AnimalMergeRepository repository;
    private final AdoptionMergeEligibilityGateway adoptionGateway;
    private final GovernanceAuthorization authorization;
    private final TransactionTemplate transactions;

    public AnimalMergeService(AnimalMergeRepository repository,
                              AdoptionMergeEligibilityGateway adoptionGateway,
                              GovernanceAuthorization authorization,
                              TransactionTemplate transactions) {
        this.repository = repository;
        this.adoptionGateway = adoptionGateway;
        this.authorization = authorization;
        this.transactions = transactions;
    }

    public AnimalMergeResult merge(String sourceAnimalId, String targetAnimalId, String reason) {
        IdentityGateway.CurrentUser actor = authorization.requireAdministrator();
        String sourceId = IdRules.requireUuid(sourceAnimalId, "sourceAnimalId");
        String targetId = IdRules.requireUuid(targetAnimalId, "targetAnimalId");
        if (sourceId.equals(targetId)) {
            throw new ValidationException("sourceAnimalId 与 targetAnimalId 不能相同");
        }
        String normalizedReason = normalizeReason(reason);

        AdoptionMergeEligibilityGateway.Eligibility eligibility =
                adoptionGateway.check(sourceId, targetId);
        if (!eligibility.eligible()) {
            throw new StateConflictException("Animal 存在活跃领养流程，无法合并");
        }

        return transactions.execute(status -> mergeLocally(
                sourceId, targetId, actor.id(), normalizedReason));
    }

    public PageResult<AnimalMergeRecord> history(String animalId, int page, int size) {
        authorization.requireAdministrator();
        String validAnimalId = IdRules.requireUuid(animalId, "animalId");
        if (page < 0) {
            throw new ValidationException("page 不能小于 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size 必须在 1 到 100 之间");
        }
        return new PageResult<>(repository.findHistory(validAnimalId, size, page * size),
                page, size, repository.countHistory(validAnimalId));
    }

    private AnimalMergeResult mergeLocally(String sourceId, String targetId, String actorId,
                                           String reason) {
        List<String> lockIds = List.of(sourceId, targetId).stream().sorted().toList();
        List<Animal> locked = repository.lockAnimals(lockIds.get(0), lockIds.get(1));
        if (locked.size() != 2) {
            throw new ResourceNotFoundException("source 或 target Animal 不存在");
        }
        Map<String, Animal> byId = locked.stream().collect(Collectors.toMap(
                Animal::id, Function.identity()));
        Animal source = byId.get(sourceId);
        Animal target = byId.get(targetId);
        if (source == null || target == null) {
            throw new ResourceNotFoundException("source 或 target Animal 不存在");
        }

        requireLocallyEligible(source, "source");
        requireLocallyEligible(target, "target");
        AdoptionMergeEligibilityGateway.Eligibility lockedEligibility =
                adoptionGateway.check(sourceId, targetId);
        if (!lockedEligibility.eligible()) {
            throw new StateConflictException("Animal 存在活跃领养流程，无法合并");
        }
        if (!source.campusId().equals(target.campusId())) {
            throw new StateConflictException("source 与 target 必须属于同一 Campus");
        }
        if (source.species() != target.species()) {
            throw new StateConflictException("source 与 target 必须具有相同 species");
        }
        if (repository.existsAsSource(sourceId) || repository.existsAsTarget(sourceId)) {
            throw new StateConflictException("source 已参与过 Animal Merge");
        }
        if (repository.existsAsSource(targetId)) {
            throw new StateConflictException("target 已作为 source 参与过 Animal Merge");
        }

        Instant mergedAt = Instant.now();
        AnimalMergeRecord record = new AnimalMergeRecord(UUID.randomUUID().toString(),
                sourceId, targetId, actorId, reason, mergedAt);
        repository.reassignMedia(sourceId, targetId);
        repository.reassignTimeline(sourceId, targetId);
        repository.consolidateFollowers(sourceId, targetId);
        repository.insert(record);
        repository.markSourceMerged(sourceId, source.version());
        return new AnimalMergeResult(record.id(), sourceId, targetId,
                IdentityStatus.MERGED, IdentityStatus.ACTIVE, record.createdAt());
    }

    private void requireLocallyEligible(Animal animal, String role) {
        if (animal.identityStatus() != IdentityStatus.ACTIVE
                || animal.adoptionStatus() != AdoptionStatus.NOT_OPEN
                || animal.currentContext() != CurrentContext.CAMPUS) {
            throw new StateConflictException(role
                    + " Animal 必须为 ACTIVE + NOT_OPEN + CAMPUS");
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            throw new ValidationException("reason 不能为空");
        }
        String normalized = reason.strip();
        if (normalized.isBlank()) {
            throw new ValidationException("reason 不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_REASON_CODE_POINTS) {
            throw new ValidationException("reason 不能超过 1000 个 Unicode code points");
        }
        return normalized;
    }
}
