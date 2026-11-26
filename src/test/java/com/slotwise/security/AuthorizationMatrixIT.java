package com.slotwise.security;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Role-based access matrix: every protected endpoint is called anonymously
 * and with each role, and must answer 401 / 403 / "allowed" as specified.
 * "Allowed" means the request passed authorization (any status other than
 * 401/403 - e.g. 400 for an empty body or 404 for a random id).
 */
class AuthorizationMatrixIT extends AbstractApiIntegrationTest {

    private enum Access { ANY_AUTHENTICATED, CUSTOMER, PROVIDER, ADMIN, CUSTOMER_PROVIDER_ADMIN }

    private record Rule(HttpMethod method, String path, Access access) {
    }

    private static final String ID = UUID.randomUUID().toString();

    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.GET, "/api/v1/users/me", Access.ANY_AUTHENTICATED),
            new Rule(HttpMethod.PUT, "/api/v1/users/me", Access.ANY_AUTHENTICATED),
            new Rule(HttpMethod.GET, "/api/v1/notifications/me", Access.ANY_AUTHENTICATED),

            new Rule(HttpMethod.POST, "/api/v1/appointments", Access.CUSTOMER),
            new Rule(HttpMethod.GET, "/api/v1/appointments/me", Access.CUSTOMER),
            new Rule(HttpMethod.PATCH, "/api/v1/appointments/" + ID + "/reschedule", Access.CUSTOMER),
            new Rule(HttpMethod.PATCH, "/api/v1/appointments/" + ID + "/cancel", Access.CUSTOMER_PROVIDER_ADMIN),
            new Rule(HttpMethod.GET, "/api/v1/appointments/" + ID, Access.CUSTOMER_PROVIDER_ADMIN),

            new Rule(HttpMethod.GET, "/api/v1/providers/me", Access.PROVIDER),
            new Rule(HttpMethod.PUT, "/api/v1/providers/me", Access.PROVIDER),
            new Rule(HttpMethod.GET, "/api/v1/providers/me/appointments", Access.PROVIDER),
            new Rule(HttpMethod.GET, "/api/v1/providers/me/schedule", Access.PROVIDER),
            new Rule(HttpMethod.GET, "/api/v1/providers/me/services", Access.PROVIDER),
            new Rule(HttpMethod.POST, "/api/v1/providers/me/services", Access.PROVIDER),
            new Rule(HttpMethod.PUT, "/api/v1/providers/me/availability", Access.PROVIDER),
            new Rule(HttpMethod.GET, "/api/v1/providers/me/availability", Access.PROVIDER),
            new Rule(HttpMethod.POST, "/api/v1/providers/me/availability/exceptions", Access.PROVIDER),
            new Rule(HttpMethod.PUT, "/api/v1/services/" + ID, Access.PROVIDER),
            new Rule(HttpMethod.DELETE, "/api/v1/services/" + ID, Access.PROVIDER),
            new Rule(HttpMethod.PATCH, "/api/v1/appointments/" + ID + "/confirm", Access.PROVIDER),
            new Rule(HttpMethod.PATCH, "/api/v1/appointments/" + ID + "/reject", Access.PROVIDER),
            new Rule(HttpMethod.PATCH, "/api/v1/appointments/" + ID + "/complete", Access.PROVIDER),

            new Rule(HttpMethod.GET, "/api/v1/admin/users", Access.ADMIN),
            new Rule(HttpMethod.GET, "/api/v1/admin/users/" + ID, Access.ADMIN),
            new Rule(HttpMethod.PATCH, "/api/v1/admin/users/" + ID + "/status", Access.ADMIN),
            new Rule(HttpMethod.GET, "/api/v1/admin/appointments", Access.ADMIN),
            // must stay last: a successful logout revokes the token used by the rows above
            new Rule(HttpMethod.POST, "/api/v1/auth/logout", Access.ANY_AUTHENTICATED));

    private TestUser customer;
    private TestUser provider;
    private TestUser admin;

    @BeforeEach
    void users() throws Exception {
        customer = registerCustomer("Matrix Customer");
        provider = registerProvider("Matrix Provider", "UTC");
        admin = createAdmin();
    }

    @Test
    void everyProtectedEndpointEnforcesItsRoles() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Rule rule : RULES) {
            check(rule, null, 401, violations);
            check(rule, customer, expectedFor(rule.access(), "CUSTOMER"), violations);
            check(rule, provider, expectedFor(rule.access(), "PROVIDER"), violations);
            check(rule, admin, expectedFor(rule.access(), "ADMIN"), violations);
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void publicCatalogueEndpointsNeedNoToken() throws Exception {
        for (String path : List.of("/api/v1/providers", "/api/v1/services", "/v3/api-docs", "/actuator/health")) {
            int status = mockMvc.perform(request(HttpMethod.GET, path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isEqualTo(200);
        }
    }

    /** -1 = allowed (anything but 401/403). */
    private static int expectedFor(Access access, String role) {
        boolean allowed = switch (access) {
            case ANY_AUTHENTICATED, CUSTOMER_PROVIDER_ADMIN -> true;
            case CUSTOMER -> role.equals("CUSTOMER");
            case PROVIDER -> role.equals("PROVIDER");
            case ADMIN -> role.equals("ADMIN");
        };
        return allowed ? -1 : 403;
    }

    private void check(Rule rule, TestUser user, int expected, List<String> violations) throws Exception {
        MockHttpServletRequestBuilder req = request(rule.method(), rule.path())
                .contentType("application/json").content("{}");
        if (user != null) {
            req = as(user, req);
        }
        int actual = mockMvc.perform(req).andReturn().getResponse().getStatus();
        boolean ok = expected == -1 ? actual != 401 && actual != 403 : actual == expected;
        if (!ok) {
            violations.add(rule.method() + " " + rule.path() + " as " + (user == null ? "anonymous" : user.email())
                    + ": expected " + (expected == -1 ? "allowed" : expected) + " but was " + actual);
        }
    }
}
