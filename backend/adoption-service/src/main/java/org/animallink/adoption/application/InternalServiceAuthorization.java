package org.animallink.adoption.application;

import org.animallink.adoption.domain.InternalServiceAccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class InternalServiceAuthorization {
    public void requireAnimalService(String caller) {
        if (!"animal-service".equals(caller)) {
            throw new InternalServiceAccessDeniedException("仅允许受信任的 animal-service 调用内部接口");
        }
    }
}
