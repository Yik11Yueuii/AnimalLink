package org.animallink.intelligence.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class IdentityClientConfiguration {
    @Bean
    RestClient identityRestClient(
            @Value("${animallink.identity.base-url}") String baseUrl,
            @Value("${animallink.identity.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${animallink.identity.read-timeout-ms}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
