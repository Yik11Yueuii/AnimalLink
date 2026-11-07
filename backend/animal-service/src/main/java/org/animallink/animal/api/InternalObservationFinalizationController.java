package org.animallink.animal.api;

import jakarta.validation.Valid;
import org.animallink.animal.application.InternalServiceAuthorization;
import org.animallink.animal.application.ObservationFinalizationService;
import org.animallink.animal.infrastructure.ObservationServiceAssertionVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.animallink.animal.domain.ForbiddenException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/internal/v1/observation-finalizations")
public class InternalObservationFinalizationController {
    private final ObservationFinalizationService service;
    private final InternalServiceAuthorization authorization;
    private final ObservationServiceAssertionVerifier assertionVerifier;

    public InternalObservationFinalizationController(
            ObservationFinalizationService service,
            InternalServiceAuthorization authorization,
            ObservationServiceAssertionVerifier assertionVerifier) {
        this.service = service;
        this.authorization = authorization;
        this.assertionVerifier = assertionVerifier;
    }

    @PostMapping
    ObservationFinalizationDtos.InternalFinalizeResponse finalizeObservation(
            @RequestHeader(name = "X-Internal-Service", required = false) String caller,
            @RequestHeader(name = "Authorization", required = false) String assertion,
            HttpServletRequest httpRequest,
            @Valid @RequestBody ObservationFinalizationDtos.InternalFinalizeRequest request) {
        authorization.requireIntelligenceService(caller);
        var command = request.toCommand();
        assertionVerifier.verify(assertion, httpRequest.getMethod(), httpRequest.getRequestURI(),
                command, body(httpRequest));
        return ObservationFinalizationDtos.InternalFinalizeResponse.from(
                service.finalizeObservation(command));
    }

    @PostMapping("/attempts/register")
    ObservationFinalizationDtos.RegisterAttemptResponse registerAttempt(
            @RequestHeader(name = "X-Internal-Service", required = false) String caller,
            @RequestHeader(name = "Authorization", required = false) String assertion,
            HttpServletRequest httpRequest,
            @Valid @RequestBody ObservationFinalizationDtos.RegisterAttemptRequest request) {
        authorization.requireIntelligenceService(caller);
        assertionVerifier.verifyOperation(assertion, httpRequest.getMethod(), httpRequest.getRequestURI(),
                new ObservationServiceAssertionVerifier.Context(request.userId(), request.sourceAiTaskId(),
                        request.campusId(), request.sourceMatchingRecordId(), request.attemptId(),
                        0, request.decisionType(), request.selectedAnimalId(), null, null),
                body(httpRequest));
        long version = service.registerAttempt(request.userId(), request.campusId(),
                request.sourceAiTaskId(), request.sourceMatchingRecordId(), request.attemptId(),
                request.decisionType(), request.selectedAnimalId());
        return new ObservationFinalizationDtos.RegisterAttemptResponse(version);
    }

    @PostMapping("/attempts/retire")
    ObservationFinalizationDtos.RetireAttemptResponse retireAttempt(
            @RequestHeader(name = "X-Internal-Service", required = false) String caller,
            @RequestHeader(name = "Authorization", required = false) String assertion,
            HttpServletRequest httpRequest,
            @Valid @RequestBody ObservationFinalizationDtos.RetireAttemptRequest request) {
        authorization.requireIntelligenceService(caller);
        assertionVerifier.verifyOperation(assertion, httpRequest.getMethod(), httpRequest.getRequestURI(),
                new ObservationServiceAssertionVerifier.Context(request.userId(), request.sourceAiTaskId(),
                        request.campusId(), request.sourceMatchingRecordId(), request.attemptId(),
                        request.fencingVersion(), request.decisionType(), request.selectedAnimalId(),
                        null, request.failureCode()), body(httpRequest));
        service.retireAttempt(request.userId(), request.sourceAiTaskId(),
                request.sourceMatchingRecordId(), request.attemptId(), request.fencingVersion(),
                request.campusId(), request.decisionType(), request.selectedAnimalId(),
                request.failureCode());
        return new ObservationFinalizationDtos.RetireAttemptResponse(true);
    }

    private byte[] body(HttpServletRequest request) {
        ContentCachingRequestWrapper cached = WebUtils.getNativeRequest(request,
                ContentCachingRequestWrapper.class);
        if (cached == null || cached.getContentAsByteArray().length == 0)
            throw new ForbiddenException("正式化请求正文未验证");
        return cached.getContentAsByteArray();
    }
}
