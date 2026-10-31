package org.animallink.identity.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.time.Duration;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WechatOfficialCodeExchangeClient implements WechatCodeExchangeClient {
    private final RestClient webClient;
    private final String appId;
    private final String appSecret;

    public WechatOfficialCodeExchangeClient(
            @Value("${animallink.wechat.app-id:}") String appId,
            @Value("${animallink.wechat.app-secret:}") String appSecret,
            @Value("${animallink.wechat.code-exchange-base-url:https://api.weixin.qq.com}") String baseUrl,
            @Value("${animallink.wechat.code-exchange-connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${animallink.wechat.code-exchange-read-timeout:PT5S}") Duration readTimeout) {
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(requireTimeout(connectTimeout)).build());
        requestFactory.setReadTimeout(requireTimeout(readTimeout));
        this.webClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("WeChat code exchange timeout is invalid");
        }
        return timeout;
    }

    @Override
    public ExchangeResult exchange(String code) {
        if (appId.isBlank() || appSecret.isBlank()) throw new UnavailableException();
        try {
            Response response = webClient.get().uri(builder -> builder.path("/sns/jscode2session")
                            .queryParam("appid", appId).queryParam("secret", appSecret)
                            .queryParam("js_code", code).queryParam("grant_type", "authorization_code").build())
                    .accept(MediaType.APPLICATION_JSON).retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, clientResponse) -> { throw new RejectedCodeException(); })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, clientResponse) -> { throw new UnavailableException(); })
                    .body(Response.class);
            if (response == null) throw new UnavailableException();
            if (response.errCode != null) throw new RejectedCodeException();
            if (response.openId == null || !response.openId.matches("[A-Za-z0-9_-]{8,128}")) {
                throw new RejectedCodeException();
            }
            return new ExchangeResult(response.openId, response.unionId);
        } catch (RejectedCodeException rejected) {
            throw rejected;
        } catch (Exception unavailable) {
            throw new UnavailableException();
        }
    }

    private static final class Response {
        @JsonProperty("openid") String openId;
        @JsonProperty("unionid") String unionId;
        @JsonProperty("errcode") Integer errCode;
    }
}
