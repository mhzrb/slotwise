package com.slotwise.user;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserManagementIT extends AbstractApiIntegrationTest {

    @Test
    void userCanUpdateOwnProfile() throws Exception {
        TestUser user = registerCustomer("Judy");

        mockMvc.perform(withJson(as(user, put("/api/v1/users/me")),
                        Map.of("fullName", "Judy Updated", "phone", "+1 416 555 0100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Judy Updated"))
                .andExpect(jsonPath("$.phone").value("+1 416 555 0100"));

        mockMvc.perform(as(user, get("/api/v1/users/me")))
                .andExpect(jsonPath("$.fullName").value("Judy Updated"));
    }

    @Test
    void changePasswordRequiresCurrentPasswordAndRevokesRefreshTokens() throws Exception {
        TestUser user = registerCustomer("Ken");

        mockMvc.perform(withJson(as(user, put("/api/v1/users/me/password")),
                        Map.of("currentPassword", "Wrong999", "newPassword", "NewSecret456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        mockMvc.perform(withJson(as(user, put("/api/v1/users/me/password")),
                        Map.of("currentPassword", PASSWORD, "newPassword", "NewSecret456")))
                .andExpect(status().isNoContent());

        mockMvc.perform(withJson(post("/api/v1/auth/refresh"), Map.of("refreshToken", user.refreshToken())))
                .andExpect(status().isUnauthorized());
        login(user.email(), "NewSecret456");
    }

    @Test
    void adminEndpointsRequireAdminRole() throws Exception {
        TestUser customer = registerCustomer("Leo");
        TestUser provider = registerProvider("Leo's Barber", "Europe/London");

        mockMvc.perform(as(customer, get("/api/v1/admin/users")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(as(provider, get("/api/v1/admin/users")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanSearchUsersWithFiltersAndPagination() throws Exception {
        TestUser admin = createAdmin();
        registerCustomer("Mia");
        registerCustomer("Noah");
        registerProvider("Mia's Studio", "Europe/Paris");

        mockMvc.perform(as(admin, get("/api/v1/admin/users").param("role", "CUSTOMER").param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(as(admin, get("/api/v1/admin/users").param("q", "mia")))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(as(admin, get("/api/v1/admin/users").param("sort", "email,asc").param("role", "CUSTOMER")))
                .andExpect(jsonPath("$.content[*].role", everyItem(is("CUSTOMER"))));

        mockMvc.perform(as(admin, get("/api/v1/admin/users").param("sort", "passwordHashX")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"));
    }

    @Test
    void disabledUserCannotLoginOrRefresh() throws Exception {
        TestUser admin = createAdmin();
        TestUser user = registerCustomer("Olivia");

        mockMvc.perform(withJson(as(admin, patch("/api/v1/admin/users/{id}/status", user.id())),
                        Map.of("enabled", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(withJson(post("/api/v1/auth/login"), Map.of("email", user.email(), "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mockMvc.perform(withJson(post("/api/v1/auth/refresh"), Map.of("refreshToken", user.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotDisableThemselves() throws Exception {
        TestUser admin = createAdmin();
        mockMvc.perform(withJson(as(admin, patch("/api/v1/admin/users/{id}/status", admin.id())),
                        Map.of("enabled", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_STATUS"));
    }
}
