package com.slotwise.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests running against a real PostgreSQL in
 * Docker. The container is started once per JVM (singleton pattern) and shared
 * by all test classes so the Spring context cache stays valid; each test starts
 * from empty tables.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE notifications, appointments, availability_exceptions, schedule_breaks,
                    weekly_schedule_days, service_offerings, provider_profiles, revoked_access_tokens,
                    refresh_tokens, users CASCADE""");
    }
}
