package com.slotwise.common.web;

import com.slotwise.common.exception.ApiException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates every exception into an RFC 9457 Problem Details response with
 * a stable {@code code}, the request {@code correlationId} and, for
 * validation failures, a list of field {@code errors}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** PostgreSQL SQLSTATE codes. */
    private static final String PG_EXCLUSION_VIOLATION = "23P01";
    private static final String PG_UNIQUE_VIOLATION = "23505";
    /** Transient concurrency failures: deadlock, lock timeout, serialization failure. Safe to retry. */
    private static final java.util.Set<String> PG_TRANSIENT = java.util.Set.of("40P01", "55P03", "40001");

    public record FieldError(String field, String message) {
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("Request failed: {}", ex.getMessage(), ex);
        } else {
            log.debug("Request rejected [{}]: {}", ex.getCode(), ex.getMessage());
        }
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        String sqlState = findSqlState(ex);
        if (PG_EXCLUSION_VIOLATION.equals(sqlState)) {
            log.info("Overlapping appointment rejected by database constraint");
            return build(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE",
                    "The requested time slot is no longer available", null);
        }
        if (PG_UNIQUE_VIOLATION.equals(sqlState)) {
            return build(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "The resource already exists", null);
        }
        log.warn("Data integrity violation (sqlState={})", sqlState, ex);
        return build(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The request conflicts with existing data", null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently; reload and retry", null);
    }

    /** Lock wait exceeded {@code lock_timeout}: transient contention, safe to retry. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handlePessimisticLock(PessimisticLockingFailureException ex) {
        log.warn("Lock acquisition failed: {}", ex.getMostSpecificCause().getMessage());
        ResponseEntity<ProblemDetail> response = build(HttpStatus.SERVICE_UNAVAILABLE, "LOCK_TIMEOUT",
                "The resource is busy; please retry", null);
        return ResponseEntity.status(response.getStatusCode()).header(HttpHeaders.RETRY_AFTER, "1")
                .body(response.getBody());
    }

    /** Any other data access failure; transient lock/deadlock states become 503 instead of 500. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException ex) {
        String sqlState = findSqlState(ex);
        if (sqlState != null && PG_TRANSIENT.contains(sqlState)) {
            log.warn("Transient database concurrency failure (sqlState={})", sqlState);
            ResponseEntity<ProblemDetail> response = build(HttpStatus.SERVICE_UNAVAILABLE, "LOCK_TIMEOUT",
                    "The resource is busy; please retry", null);
            return ResponseEntity.status(response.getStatusCode()).header(HttpHeaders.RETRY_AFTER, "1")
                    .body(response.getBody());
        }
        log.error("Data access failure (sqlState={})", sqlState, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", null);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ProblemDetail> handleAccessDenied(RuntimeException ex) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this action", null);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ResponseEntity<ProblemDetail> handleBadSort(PropertyReferenceException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_SORT_PROPERTY",
                "Unknown sort property: " + ex.getPropertyName(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "'", null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", null);
    }

    // ----- Spring MVC exceptions (overrides of ResponseEntityExceptionHandler) -----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errors.add(new FieldError(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> errors.add(new FieldError(e.getObjectName(), e.getDefaultMessage())));
        return asObject(build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(err ->
                errors.add(new FieldError(result.getMethodParameter().getParameterName(), err.getDefaultMessage()))));
        return asObject(build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or malformed", null));
    }

    /** Enrich the ProblemDetail of all other Spring MVC exceptions (404 route, 405, 415, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            enrich(problem, status != null ? status.name() : "HTTP_" + statusCode.value(), null);
        }
        return response;
    }

    // ----- helpers -----

    private static ResponseEntity<ProblemDetail> build(HttpStatus status, String code, String detail,
            List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        enrich(problem, code, errors);
        return ResponseEntity.status(status).body(problem);
    }

    private static void enrich(ProblemDetail problem, String code, List<FieldError> errors) {
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> response) {
        return (ResponseEntity) response;
    }

    private static String findSqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String lastNode(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** Exposed for the security entry points, which run outside the MVC layer. */
    public static Map<String, Object> problemProperties(String code) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId == null
                ? Map.of("code", code, "timestamp", Instant.now().toString())
                : Map.of("code", code, "timestamp", Instant.now().toString(), "correlationId", correlationId);
    }
}
