package org.animallink.intelligence.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MatchingWeightProperties.class)
public class MatchingConfiguration {
}
