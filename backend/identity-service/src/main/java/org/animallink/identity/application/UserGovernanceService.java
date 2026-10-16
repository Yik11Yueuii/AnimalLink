package org.animallink.identity.application;

import jakarta.validation.ValidationException;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.ConflictException;
import org.animallink.identity.domain.ForbiddenException;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserGovernanceAction;
import org.animallink.identity.domain.UserGovernanceAudit;
import org.animallink.identity.domain.UserGovernanceRepository;
import org.animallink.identity.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class UserGovernanceService {
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final UserGovernanceRepository governanceRepository;

    public UserGovernanceService(CurrentUserProvider currentUserProvider,
                                 UserRepository userRepository,
                                 UserGovernanceRepository governanceRepository) {
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
        this.governanceRepository = governanceRepository;
    }

    @Transactional
    public UserAccount restrict(String userId, String reason) {
        UserAccount actor = requireGovernanceAdmin();
        String targetId = IdRules.requireUuid(userId, "userId");
        String normalizedReason = normalizeReason(reason);
        UserAccount target = lockTarget(targetId);
        if (actor.id().equals(target.id())) {
            throw new ConflictException("治理管理员不能限制自己的账号");
        }
        if (target.isGovernanceAdmin()) {
            throw new ConflictException("不能限制其他治理管理员账号");
        }
        return transition(target, actor, UserGovernanceAction.RESTRICT,
                AccountStatus.ACTIVE, AccountStatus.SUSPENDED, normalizedReason);
    }

    @Transactional
    public UserAccount unrestrict(String userId, String reason) {
        UserAccount actor = requireGovernanceAdmin();
        String targetId = IdRules.requireUuid(userId, "userId");
        String normalizedReason = normalizeReason(reason);
        UserAccount target = lockTarget(targetId);
        return transition(target, actor, UserGovernanceAction.UNRESTRICT,
                AccountStatus.SUSPENDED, AccountStatus.ACTIVE, normalizedReason);
    }

    public UserAccount detail(String userId) {
        requireGovernanceAdmin();
        String targetId = IdRules.requireUuid(userId, "userId");
        return findTarget(targetId);
    }

    public UserGovernanceHistoryPage history(String userId, int page, int size) {
        requireGovernanceAdmin();
        String targetId = IdRules.requireUuid(userId, "userId");
        findTarget(targetId);
        if (page < 0) {
            throw new ValidationException("page 不能小于 0");
        }
        if (size < 1 || size > 100) {
            throw new ValidationException("size 必须在 1 到 100 之间");
        }
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            throw new ValidationException("page 超出允许范围");
        }
        return new UserGovernanceHistoryPage(
                governanceRepository.findAudits(targetId, size, (int) offset),
                page, size, governanceRepository.countAudits(targetId));
    }

    private UserAccount transition(UserAccount target, UserAccount actor,
                                   UserGovernanceAction action, AccountStatus expected,
                                   AccountStatus next, String reason) {
        if (target.accountStatus() != expected) {
            throw new ConflictException("不允许的用户账号状态迁移");
        }
        if (governanceRepository.transitionStatus(target.id(), expected, next) != 1) {
            throw new ConflictException("用户账号状态已发生变化");
        }
        governanceRepository.insertAudit(new UserGovernanceAudit(
                UUID.randomUUID().toString(), target.id(), action, expected, next,
                actor.id(), reason, Instant.now()));
        return governanceRepository.findUserByIdForUpdate(target.id())
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    private UserAccount requireGovernanceAdmin() {
        UserAccount actor = currentUserProvider.requireCurrentUser();
        if (!actor.isGovernanceAdmin()) {
            throw new ForbiddenException("仅治理管理员可以管理用户账号");
        }
        return actor;
    }

    private UserAccount lockTarget(String userId) {
        return governanceRepository.findUserByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    private UserAccount findTarget(String userId) {
        return userRepository.findUserById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            throw new ValidationException("reason 不能为空");
        }
        String normalized = reason.strip();
        if (normalized.isBlank()) {
            throw new ValidationException("reason 不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > 1000) {
            throw new ValidationException("reason 不能超过 1000 个字符");
        }
        return normalized;
    }
}
