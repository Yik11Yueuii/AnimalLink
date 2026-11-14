package org.animallink.incident.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class MissingParameterExceptionHandler {
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<IncidentExceptionHandler.Error> missingParameter() {
        return ResponseEntity.badRequest()
                .body(new IncidentExceptionHandler.Error("VALIDATION_ERROR", "请求参数无效"));
    }
}
