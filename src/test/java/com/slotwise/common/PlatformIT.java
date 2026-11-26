package com.slotwise.common;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cross-cutting platform behaviour: API docs, health, error format for framework-level errors. */
class PlatformIT extends AbstractApiIntegrationTest {

    @Test
    void openApiDocumentDescribesTheApiAndBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Slotwise API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/appointments'].post", notNullValue()))
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/slots'].get", notNullValue()))
                // the JWT-derived principal must not leak into the documented parameters
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("AuthenticatedUser"))));
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void frameworkErrorsUseProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        TestUser user = registerCustomer("Platform");
        mockMvc.perform(as(user, get("/api/v1/auth/login")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(as(user, get("/api/v1/no-such-endpoint")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void publicListingsRejectNonWhitelistedSortProperties() throws Exception {
        mockMvc.perform(get("/api/v1/providers").param("sort", "user.email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"));
        mockMvc.perform(get("/api/v1/services").param("sort", "provider.user.passwordHash"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/services").param("sort", "price,desc"))
                .andExpect(status().isOk());
    }
}
