package com.slotwise.schema;

import com.slotwise.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Flyway schema itself - independent of application code - so
 * that database-level guarantees cannot regress silently.
 */
class DatabaseSchemaIT extends AbstractIntegrationTest {

    private UUID providerId;
    private UUID customerId;
    private UUID serviceId;
    private final Instant base = Instant.parse("2030-01-07T15:00:00Z");

    @BeforeEach
    void seed() {
        UUID providerUser = insertUser("provider@example.com", "PROVIDER");
        customerId = insertUser("customer@example.com", "CUSTOMER");
        providerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO provider_profiles (id, user_id, business_name, time_zone, created_at, updated_at)
                VALUES (?, ?, 'Clinic', 'America/Toronto', now(), now())""", providerId, providerUser);
        serviceId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO service_offerings (id, provider_id, name, duration_minutes, buffer_minutes, price,
                    created_at, updated_at)
                VALUES (?, ?, 'Consultation', 30, 10, 50.00, now(), now())""", serviceId, providerId);
    }

    @Test
    void allMigrationsApplied() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(5);
    }

    @Test
    void exclusionConstraintRejectsOverlappingActiveAppointments() {
        insertAppointment(base, 30, 10, "CONFIRMED");

        // starts during the buffer of the first appointment -> overlaps [start, blocked_until)
        assertThatThrownBy(() -> insertAppointment(base.plus(35, ChronoUnit.MINUTES), 30, 0, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("23P01"));
    }

    @Test
    void exclusionConstraintAllowsAdjacentAppointments() {
        insertAppointment(base, 30, 10, "CONFIRMED");
        // exactly at blocked_until: half-open ranges [) do not overlap
        insertAppointment(base.plus(40, ChronoUnit.MINUTES), 30, 0, "PENDING");

        assertThat(countAppointments()).isEqualTo(2);
    }

    @Test
    void exclusionConstraintIgnoresInactiveAppointments() {
        insertAppointment(base, 30, 10, "CANCELLED");
        insertAppointment(base, 30, 10, "REJECTED");
        insertAppointment(base, 30, 10, "CONFIRMED");

        assertThat(countAppointments()).isEqualTo(3);
    }

    @Test
    void checkConstraintRejectsUnknownStatus() {
        assertThatThrownBy(() -> insertAppointment(base, 30, 0, "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailMustBeLowercaseAndUnique() {
        assertThatThrownBy(() -> insertUser("Mixed@Example.com", "CUSTOMER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser("customer@example.com", "CUSTOMER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at)
                VALUES (?, ?, 'x', 'Test User', ?, now(), now())""", id, email, role);
        return id;
    }

    private void insertAppointment(Instant start, int durationMinutes, int bufferMinutes, String status) {
        Instant end = start.plus(durationMinutes, ChronoUnit.MINUTES);
        Instant blockedUntil = end.plus(bufferMinutes, ChronoUnit.MINUTES);
        jdbcTemplate.update("""
                INSERT INTO appointments (id, provider_id, customer_id, service_id, start_at, end_at, blocked_until,
                    status, price, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 50.00, now(), now())""",
                UUID.randomUUID(), providerId, customerId, serviceId,
                Timestamp.from(start), Timestamp.from(end), Timestamp.from(blockedUntil), status);
    }

    private int countAppointments() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM appointments", Integer.class);
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
