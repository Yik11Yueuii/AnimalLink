package org.animallink.identity.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class IntelligenceClientConfiguration {
    @Bean
    @Qualifier("intelligenceRestClient")
    RestClient intelligenceRestClient(
            @Value("${animallink.intelligence.base-url:http://localhost:8085}") String baseUrl,
            @Value("${animallink.intelligence.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${animallink.intelligence.read-timeout-ms:5000}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
