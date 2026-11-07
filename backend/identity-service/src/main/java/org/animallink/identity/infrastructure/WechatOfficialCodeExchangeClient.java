package org.animallink.identity.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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

    @Autowired
    public WechatOfficialCodeExchangeClient(
            @Value("${animallink.wechat.app-id:}") String appId,
            @Value("${animallink.wechat.app-secret:}") String appSecret,
            @Value("${animallink.wechat.code-exchange-base-url:https://api.weixin.qq.com}") String baseUrl,
            @Value("${animallink.wechat.code-exchange-connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${animallink.wechat.code-exchange-read-timeout:PT5S}") Duration readTimeout) {
        this(appId, appSecret, httpsClient(baseUrl, connectTimeout, readTimeout));
    }

    // Test code supplies a loopback RestClient directly; no runtime configuration can enable HTTP.
    WechatOfficialCodeExchangeClient(String appId, String appSecret, RestClient webClient) {
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.webClient = webClient;
    }

    private static RestClient httpsClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        URI uri = requireHttpsBaseUrl(baseUrl);
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(requireTimeout(connectTimeout)).build());
        requestFactory.setReadTimeout(requireTimeout(readTimeout));
        return RestClient.builder().baseUrl(uri.toString()).requestFactory(requestFactory).build();
    }

    private static URI requireHttpsBaseUrl(String baseUrl) {
        try {
            URI uri = new URI(baseUrl == null ? "" : baseUrl);
            if (!uri.isAbsolute() || uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("WeChat code exchange base URL must be an absolute HTTPS URL without credentials, query, or fragment");
        }
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
                            .queryParam("appid", "{appId}").queryParam("secret", "{appSecret}")
                            .queryParam("js_code", "{code}").queryParam("grant_type", "authorization_code")
                            .build(appId, appSecret, code))
                    .accept(MediaType.APPLICATION_JSON).retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection,
                            (request, clientResponse) -> { throw new UnavailableException(); })
                    .onStatus(status -> status.equals(HttpStatus.TOO_MANY_REQUESTS),
                            (request, clientResponse) -> { throw new RateLimitedException(); })
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, clientResponse) -> { throw new UnavailableException(); })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, clientResponse) -> { throw new UnavailableException(); })
                    .body(Response.class);
            if (response == null) throw new UnavailableException();
            if (response.errCode != null) {
                switch (response.errCode) {
                    case 0 -> { }
                    case 40029, 40163 -> throw new RejectedCodeException();
                    case 40226 -> throw new RiskBlockedException();
                    case 45011 -> throw new RateLimitedException();
                    default -> throw new UnavailableException();
                }
            }
            if (response.openId == null || !response.openId.matches("[A-Za-z0-9_-]{8,128}")) {
                throw new UnavailableException();
            }
            return new ExchangeResult(response.openId, response.unionId);
        } catch (RejectedCodeException | RiskBlockedException | RateLimitedException | UnavailableException classified) {
            throw classified;
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
