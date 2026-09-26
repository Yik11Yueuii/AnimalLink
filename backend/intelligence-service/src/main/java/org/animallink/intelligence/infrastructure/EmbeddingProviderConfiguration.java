package org.animallink.intelligence.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmbeddingProviderProperties.class)
public class EmbeddingProviderConfiguration {
}
