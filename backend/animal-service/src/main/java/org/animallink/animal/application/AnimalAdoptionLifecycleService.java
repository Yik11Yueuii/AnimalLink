package org.animallink.animal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.animallink.animal.domain.AdoptionStatus;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalAdoptionStatusCommand;
import org.animallink.animal.domain.AnimalAdoptionStatusCommand.Action;
import org.animallink.animal.domain.AnimalAdoptionStatusCommandRepository;
import org.animallink.animal.domain.AnimalRepository;
import org.animallink.animal.domain.IdempotencyKeyReusedException;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.domain.StateConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnimalAdoptionLifecycleService {
    private static final int MAX_REASON_CODE_POINTS = 1000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final AnimalRepository animals;
    private final AnimalAdoptionStatusCommandRepository commands;
    private final AdoptionMergeEligibilityGateway adoption;
    private final GovernanceAuthorization authorization;
    private final TransactionTemplate transactions;

    public AnimalAdoptionLifecycleService(AnimalRepository animals,
                                          AnimalAdoptionStatusCommandRepository commands,
                                          AdoptionMergeEligibilityGateway adoption,
                                          GovernanceAuthorization authorization,
                                          TransactionTemplate transactions) {
        this.animals = animals;
        this.commands = commands;
        this.adoption = adoption;
        this.authorization = authorization;
        this.transactions = transactions;
    }

    public Result open(String animalId, String reason, String idempotencyKey) {
        return transition(animalId, reason, idempotencyKey, Action.OPEN);
    }

    public Result close(String animalId, String reason, String idempotencyKey) {
        return transition(animalId, reason, idempotencyKey, Action.CLOSE);
    }

    private Result transition(String rawAnimalId, String rawReason, String rawKey, Action action) {
        IdentityGateway.CurrentUser actor = authorization.requireAdministrator();
        String animalId = IdRules.requireUuid(rawAnimalId, "animalId");
        String reason = normalizeReason(rawReason);
        String key = normalizeIdempotencyKey(rawKey);
        String hash = requestHash(animalId, action, reason);

        var previous = commands.findByKey(key);
        if (previous.isPresent()) {
            return replay(previous.get(), hash);
        }

        Animal observed = requiredAnimal(animalId);
        apply(observed, action);
        requireNoAdoptionBlockers(animalId);

        try {
            return transactions.execute(status -> {
                Animal locked = animals.findByIdForUpdate(animalId)
                        .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在"));
                var existing = commands.findByKeyForUpdate(key);
                if (existing.isPresent()) {
                    return replay(existing.get(), hash);
                }

                Animal changed = apply(locked, action);
                requireNoAdoptionBlockers(animalId);
                animals.update(changed);

                Instant occurredAt = Instant.now();
                AnimalAdoptionStatusCommand command = new AnimalAdoptionStatusCommand(
                        key, hash, animalId, action, locked.adoptionStatus(),
                        changed.adoptionStatus(), actor.id(), reason, locked.version() + 1,
                        occurredAt);
                commands.insert(command);
                return result(command, false);
            });
        } catch (DataIntegrityViolationException exception) {
            AnimalAdoptionStatusCommand winner = commands.findByKey(key).orElseThrow(() -> exception);
            return replay(winner, hash);
        }
    }

    private Animal requiredAnimal(String animalId) {
        return animals.findById(animalId)
                .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在"));
    }

    private Animal apply(Animal animal, Action action) {
        return action == Action.OPEN ? animal.openAdoption() : animal.closeAdoption();
    }

    private void requireNoAdoptionBlockers(String animalId) {
        if (!adoption.checkAnimal(animalId).eligible()) {
            throw new StateConflictException("Animal 存在活跃领养流程，无法变更领养状态");
        }
    }

    private Result replay(AnimalAdoptionStatusCommand command, String requestHash) {
        if (!command.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException("Idempotency-Key 已用于不同的领养状态请求");
        }
        return result(command, true);
    }

    private Result result(AnimalAdoptionStatusCommand command, boolean replayed) {
        return new Result(command.animalId(), command.action(), command.fromStatus(),
                command.toStatus(), command.resultVersion(), command.reason(),
                command.createdAt(), replayed);
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        String normalized = reason.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_REASON_CODE_POINTS) {
            throw new IllegalArgumentException("reason 不能超过 1000 个 Unicode code points");
        }
        return normalized;
    }

    private static String normalizeIdempotencyKey(String rawKey) {
        if (rawKey == null) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        String key = rawKey.strip();
        if (key.isEmpty() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || key.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
            throw new IllegalArgumentException("Idempotency-Key 必须为 1 到 128 个可打印 ASCII 字符");
        }
        return key;
    }

    private static String requestHash(String animalId, Action action, String reason) {
        String canonical = "animalId=" + animalId + "\naction=" + action.name()
                + "\nreason=" + reason;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record Result(String animalId, Action action, AdoptionStatus fromStatus,
                         AdoptionStatus toStatus, int version, String reason,
                         Instant occurredAt, boolean replayed) {
    }
}
