package org.animallink.intelligence.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AnimalClientConfiguration {
    @Bean
    @Qualifier("animalRestClient")
    RestClient animalRestClient(
            @Value("${animallink.animal.base-url}") String baseUrl,
            @Value("${animallink.animal.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${animallink.animal.read-timeout-ms}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
