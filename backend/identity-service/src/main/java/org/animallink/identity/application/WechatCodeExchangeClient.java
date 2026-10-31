package org.animallink.identity.application;

/** Verifies one short-lived wx.login code at WeChat's server-side endpoint. */
public interface WechatCodeExchangeClient {
    ExchangeResult exchange(String code);

    record ExchangeResult(String openId, String unionId) {
    }

    final class RejectedCodeException extends RuntimeException {
    }

    final class UnavailableException extends RuntimeException {
    }
}
