package com.slotwise.common;

import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;

import java.lang.reflect.Method;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void exclusionViolationBecomesSlotUnavailable() throws Exception {
        ResponseEntity<ProblemDetail> response = invoke("handleDataIntegrity", DataIntegrityViolationException.class,
                new DataIntegrityViolationException("x", new SQLException("overlap", "23P01")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("code", "SLOT_UNAVAILABLE");
    }

    @Test
    void deadlockAndLockTimeoutBecomeRetryable503() throws Exception {
        for (String state : new String[]{"40P01", "55P03", "40001"}) {
            ResponseEntity<ProblemDetail> response = invoke("handleDataAccess",
                    org.springframework.dao.DataAccessException.class,
                    new UncategorizedSQLException("task", "sql", new SQLException("busy", state)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(response.getBody().getProperties()).containsEntry("code", "LOCK_TIMEOUT");
        }
    }

    @Test
    void unknownDataAccessErrorsAreInternalErrorsWithoutDetails() throws Exception {
        ResponseEntity<ProblemDetail> response = invoke("handleDataAccess",
                org.springframework.dao.DataAccessException.class,
                new UncategorizedSQLException("task", "SELECT secret", new SQLException("boom", "XX000")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).doesNotContain("SELECT", "boom");
    }

    @Test
    void apiExceptionsKeepTheirStatusAndCode() throws Exception {
        ResponseEntity<ProblemDetail> response = invoke("handleApiException",
                com.slotwise.common.exception.ApiException.class, new ConflictException("SOME_CODE", "msg"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("code", "SOME_CODE");
        assertThat(response.getBody().getDetail()).isEqualTo("msg");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<ProblemDetail> invoke(String name, Class<?> type, Object arg) throws Exception {
        Method method = GlobalExceptionHandler.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return (ResponseEntity<ProblemDetail>) method.invoke(handler, arg);
    }
}
