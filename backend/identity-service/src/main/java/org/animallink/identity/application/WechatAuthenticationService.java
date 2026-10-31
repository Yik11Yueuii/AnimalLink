package org.animallink.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.WechatAuthenticationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WechatAuthenticationService {
    private final WechatCodeExchangeClient codeExchangeClient;
    private final WechatAuthenticationRepository repository;
    private final OpaqueSessionTokenCodec tokenCodec;
    private final AuthenticationClock clock;
    private final String appId;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;

    public WechatAuthenticationService(WechatCodeExchangeClient codeExchangeClient,
                                       WechatAuthenticationRepository repository,
                                       OpaqueSessionTokenCodec tokenCodec, AuthenticationClock clock,
                                       @Value("${animallink.wechat.app-id:}") String appId,
                                       @Value("${animallink.wechat.session.idle-timeout:PT168H}") Duration idleTimeout,
                                       @Value("${animallink.wechat.session.absolute-timeout:PT720H}") Duration absoluteTimeout) {
        if (idleTimeout == null || absoluteTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero() || absoluteTimeout.isNegative()
                || absoluteTimeout.isZero() || idleTimeout.compareTo(absoluteTimeout) > 0) {
            throw new IllegalArgumentException("WeChat authentication configuration is invalid");
        }
        this.codeExchangeClient = codeExchangeClient; this.repository = repository; this.tokenCodec = tokenCodec;
        this.clock = clock; this.appId = appId.trim(); this.idleTimeout = idleTimeout; this.absoluteTimeout = absoluteTimeout;
    }

    @Transactional
    public IssuedSession login(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{8,128}")) throw new UnauthorizedException("无效微信登录凭据");
        if (appId.isBlank()) throw new WechatCodeExchangeClient.UnavailableException();
        var exchange = codeExchangeClient.exchange(code);
        Instant now = clock.now();
        var user = repository.createWechatUserIfAbsent(appId, exchange.openId(), "微信用户", now);
        Instant absolute = now.plus(absoluteTimeout); Instant idle = earlier(now.plus(idleTimeout), absolute);
        for (int attempt=0; attempt<3; attempt++) {
            String token = tokenCodec.generate();
            try {
                if (repository.insertSession(new WechatAuthenticationRepository.UserSession(UUID.randomUUID().toString(), user.id(),
                        tokenCodec.hash(token), now, now, idle, absolute, null, null, 0))) {
                    return new IssuedSession(token, user.id(), user.displayName(), idle, absolute);
                }
            } catch (DuplicateKeyException ignored) { }
        }
        throw new IllegalStateException("Unable to allocate WeChat session");
    }

    @Transactional
    public AuthenticatedSession validate(String token) {
        String hash = hash(token); Instant now = clock.now();
        var current = repository.findValidatedSession(hash, now).orElseThrow(WechatAuthenticationService::invalid);
        if (!repository.touchIfValid(hash, now, earlier(now.plus(idleTimeout), current.session().absoluteExpiresAt()))) throw invalid();
        var refreshed = repository.findValidatedSession(hash, now).orElseThrow(WechatAuthenticationService::invalid);
        return new AuthenticatedSession(refreshed.user().id(), refreshed.user().displayName(), refreshed.session().idleExpiresAt(), refreshed.session().absoluteExpiresAt());
    }

    @Transactional
    public boolean logout(String token) { return repository.revokeByTokenHash(hash(token), clock.now(), "WECHAT_MINIPROGRAM_LOGOUT"); }
    private String hash(String token) { try { return tokenCodec.hash(token); } catch (IllegalArgumentException e) { throw invalid(); } }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private static UnauthorizedException invalid() { return new UnauthorizedException("普通用户会话无效或已过期"); }
    public record IssuedSession(String token, String userId, String displayName, Instant idleExpiresAt, Instant absoluteExpiresAt) {}
    public record AuthenticatedSession(String userId, String displayName, Instant idleExpiresAt, Instant absoluteExpiresAt) {}
}
