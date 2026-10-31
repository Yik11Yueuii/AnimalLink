package org.animallink.identity.domain;

import java.time.Instant;
import java.util.Optional;

/** Persistence boundary for ordinary WeChat Mini Program identities and sessions. */
public interface WechatAuthenticationRepository {
    Optional<UserAccount> findUserByWechatSubject(String appId, String openId);

    UserAccount createWechatUserIfAbsent(String appId, String openId, String displayName, Instant now);

    boolean insertSession(UserSession session);

    Optional<ValidatedSession> findValidatedSession(String tokenHash, Instant now);

    boolean touchIfValid(String tokenHash, Instant now, Instant proposedIdleExpiry);

    boolean revokeByTokenHash(String tokenHash, Instant revokedAt, String reason);

    record UserSession(String id, String userId, String tokenHash, Instant createdAt, Instant lastSeenAt,
                       Instant idleExpiresAt, Instant absoluteExpiresAt, Instant revokedAt,
                       String revokeReason, int version) {
    }

    record ValidatedSession(UserSession session, UserAccount user) {
    }
}
