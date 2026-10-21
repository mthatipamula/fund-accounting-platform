package com.fundaccounting.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Consistent RFC 7807 error responses across services. Internal details are never returned to clients. */
@AutoConfiguration
@RestControllerAdvice
public class ApiErrors {

    /** A business rule failure the caller can fix (409) or a missing resource (404). */
    public static class ApiException extends RuntimeException {
        public final HttpStatus status;

        public ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, what + " not found"); }

        public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }

        public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        return ProblemDetail.forStatusAndDetail(e.status, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, String> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        p.setProperty("fields", fields);
        return p;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The change conflicts with existing data");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail stale(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Someone else changed this record; reload and try again");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegal(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
