package org.animallink.identity.infrastructure;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EnvironmentInfoContributor implements InfoContributor {
    private final Environment environment;

    public EnvironmentInfoContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("animallink", Map.of(
                "environment", environment.getProperty("animallink.environment", "local-fallback")));
    }
}
