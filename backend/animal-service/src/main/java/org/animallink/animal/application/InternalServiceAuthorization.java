package org.animallink.animal.application;

import org.animallink.animal.domain.ForbiddenException;
import org.springframework.stereotype.Service;

@Service
public class InternalServiceAuthorization {
    public void requireIntelligenceService(String caller) {
        if (!"intelligence-service".equals(caller)) {
            throw new ForbiddenException("仅允许受信任的 intelligence-service 调用内部接口");
        }
    }
}
