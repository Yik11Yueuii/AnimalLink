package org.animallink.identity.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
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
            @Value("${animallink.wechat.code-exchange-base-url:https://api.weixin.qq.com}") String baseUrl) {
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.webClient = RestClient.builder().baseUrl(baseUrl).build();
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
