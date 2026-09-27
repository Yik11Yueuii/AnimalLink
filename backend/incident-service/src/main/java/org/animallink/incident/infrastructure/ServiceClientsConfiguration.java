package org.animallink.incident.infrastructure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
@Configuration public class ServiceClientsConfiguration {
 @Bean RestClient identityRestClient(@Value("${animallink.identity.base-url:http://localhost:8081}") String url){return RestClient.builder().baseUrl(url).build();}
 @Bean RestClient animalRestClient(@Value("${animallink.animal.base-url:http://localhost:8082}") String url){return RestClient.builder().baseUrl(url).build();}
}
