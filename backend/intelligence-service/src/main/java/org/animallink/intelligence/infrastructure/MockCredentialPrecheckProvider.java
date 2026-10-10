package org.animallink.intelligence.infrastructure;

import org.animallink.intelligence.application.CredentialPrecheckProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Local deterministic adapter for the execution contract; a remote adapter remains out of scope. */
@Component
public class MockCredentialPrecheckProvider implements CredentialPrecheckProvider {
    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public String modelName() {
        return "local-credential-precheck-v1";
    }

    @Override
    public ProviderResult precheck(ProviderRequest request) {
        return new ProviderResult(new BigDecimal("0.95"), request.campusName(),
                request.applicantName(), "CAMPUS_CREDENTIAL", List.of());
    }
}
