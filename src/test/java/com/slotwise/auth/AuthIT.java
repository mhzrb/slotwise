package com.slotwise.auth;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIT extends AbstractApiIntegrationTest {

    @Test
    void registerReturnsTokensAndStoresHashedPassword() throws Exception {
        String email = uniqueEmail("alice");
        mockMvc.perform(withJson(post("/api/v1/auth/register"),
                        Map.of("email", email, "password", PASSWORD, "fullName", "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());

        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?",
                String.class, email);
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
    }

    @Test
    void emailIsCaseInsensitiveAndUnique() throws Exception {
        String email = uniqueEmail("bob");
        registerCustomerWithEmail(email);

        mockMvc.perform(withJson(post("/api/v1/auth/register"),
                        Map.of("email", email.toUpperCase(), "password", PASSWORD, "fullName", "Bob")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registrationValidatesInput() throws Exception {
        mockMvc.perform(withJson(post("/api/v1/auth/register"),
                        Map.of("email", "not-an-email", "password", "short", "fullName", "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fullName")))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void cannotSelfRegisterAsAdmin() throws Exception {
        mockMvc.perform(withJson(post("/api/v1/auth/register"),
                        Map.of("email", uniqueEmail("mallory"), "password", PASSWORD, "fullName", "M",
                                "role", "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailIsIndistinguishable() throws Exception {
        TestUser user = registerCustomer("Carol");

        mockMvc.perform(withJson(post("/api/v1/auth/login"), Map.of("email", user.email(), "password", "Wrong123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        mockMvc.perform(withJson(post("/api/v1/auth/login"),
                        Map.of("email", "nobody@example.com", "password", "Wrong123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void protectedEndpointRequiresValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());

        TestUser user = registerCustomer("Dave");
        mockMvc.perform(as(user, get("/api/v1/users/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.email()));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        TestUser user = registerCustomer("Eve");
        String[] parts = user.accessToken().split("\\.");
        // flip the signature
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTokens() throws Exception {
        TestUser user = registerCustomer("Frank");

        var result = mockMvc.perform(withJson(post("/api/v1/auth/refresh"),
                        Map.of("refreshToken", user.refreshToken())))
                .andExpect(status().isOk())
                .andReturn();
        String newRefresh = read(result, "$.refreshToken");
        String newAccess = read(result, "$.accessToken");
        assertThat(newRefresh).isNotEqualTo(user.refreshToken());

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andExpect(status().isOk());
    }

    @Test
    void reusingRotatedRefreshTokenRevokesWholeFamily() throws Exception {
        TestUser user = registerCustomer("Grace");
        var rotated = mockMvc.perform(withJson(post("/api/v1/auth/refresh"),
                        Map.of("refreshToken", user.refreshToken())))
                .andExpect(status().isOk())
                .andReturn();
        String legitimateNewToken = read(rotated, "$.refreshToken");

        // an attacker replays the old token
        mockMvc.perform(withJson(post("/api/v1/auth/refresh"), Map.of("refreshToken", user.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));

        // ... which also invalidates the token issued after the rotation
        mockMvc.perform(withJson(post("/api/v1/auth/refresh"), Map.of("refreshToken", legitimateNewToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        mockMvc.perform(withJson(post("/api/v1/auth/refresh"), Map.of("refreshToken", "bogus")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutInvalidatesAccessAndRefreshToken() throws Exception {
        TestUser user = registerCustomer("Heidi");

        mockMvc.perform(withJson(as(user, post("/api/v1/auth/logout")), Map.of("refreshToken", user.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(user, get("/api/v1/users/me")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(withJson(post("/api/v1/auth/refresh"), Map.of("refreshToken", user.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutDoesNotAffectOtherSessions() throws Exception {
        TestUser firstSession = registerCustomer("Ivan");
        TestUser secondSession = login(firstSession.email(), PASSWORD);

        mockMvc.perform(withJson(as(firstSession, post("/api/v1/auth/logout")),
                        Map.of("refreshToken", firstSession.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(secondSession, get("/api/v1/users/me"))).andExpect(status().isOk());
        mockMvc.perform(withJson(post("/api/v1/auth/refresh"), Map.of("refreshToken", secondSession.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void responsesCarryCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("X-Correlation-Id", "trace-123"))
                .andExpect(header().string("X-Correlation-Id", "trace-123"))
                .andExpect(jsonPath("$.correlationId").value("trace-123"));
        // unsafe ids are replaced rather than echoed into logs
        mockMvc.perform(get("/api/v1/users/me").header("X-Correlation-Id", "bad id\nINJECTED"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.not("bad id\nINJECTED")));
    }

    private void registerCustomerWithEmail(String email) throws Exception {
        mockMvc.perform(withJson(post("/api/v1/auth/register"),
                        Map.of("email", email, "password", PASSWORD, "fullName", "Bob")))
                .andExpect(status().isCreated());
    }
}
