package com.slotwise.catalog;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServiceManagementIT extends AbstractApiIntegrationTest {

    @Test
    void providerCreatesUpdatesAndDeactivatesService() throws Exception {
        TestUser provider = registerProvider("Glow Spa", "America/Toronto");

        MvcResult created = mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/services")),
                        service("Massage", 60, 15, "80.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/services/")))
                .andExpect(jsonPath("$.name").value("Massage"))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.bufferMinutes").value(15))
                .andExpect(jsonPath("$.price").value(80.00))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        String serviceId = read(created, "$.id");

        mockMvc.perform(withJson(as(provider, put("/api/v1/services/{id}", serviceId)),
                        service("Deep Tissue Massage", 90, 10, "120.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Deep Tissue Massage"))
                .andExpect(jsonPath("$.price").value(120.50));

        mockMvc.perform(get("/api/v1/services/{id}", serviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerName").value("Glow Spa"));

        mockMvc.perform(as(provider, delete("/api/v1/services/{id}", serviceId)))
                .andExpect(status().isNoContent());

        // hidden from the public catalogue ...
        mockMvc.perform(get("/api/v1/services/{id}", serviceId)).andExpect(status().isNotFound());
        // ... but still visible to the owner, as inactive
        mockMvc.perform(as(provider, get("/api/v1/providers/me/services")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void duplicateServiceNameIsRejectedCaseInsensitively() throws Exception {
        TestUser provider = registerProvider("Cuts", "Europe/London");
        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/services")), service("Haircut", 30, 0, "25")))
                .andExpect(status().isCreated());

        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/services")), service("HAIRCUT", 45, 0, "30")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_NAME_TAKEN"));

        // another provider may use the same name
        TestUser other = registerProvider("Other Cuts", "Europe/London");
        mockMvc.perform(withJson(as(other, post("/api/v1/providers/me/services")), service("Haircut", 30, 0, "25")))
                .andExpect(status().isCreated());
    }

    @Test
    void providerCannotModifyAnotherProvidersService() throws Exception {
        TestUser owner = registerProvider("Owner Co", "Europe/Berlin");
        TestUser intruder = registerProvider("Intruder Co", "Europe/Berlin");
        String serviceId = createService(owner, "Yoga", 60, 0, "20");

        mockMvc.perform(withJson(as(intruder, put("/api/v1/services/{id}", serviceId)), service("Hacked", 60, 0, "0")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(as(intruder, delete("/api/v1/services/{id}", serviceId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customersCannotManageServices() throws Exception {
        TestUser customer = registerCustomer("Paul");
        mockMvc.perform(withJson(as(customer, post("/api/v1/providers/me/services")), service("X", 30, 0, "1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(withJson(post("/api/v1/providers/me/services"), service("X", 30, 0, "1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceValidation() throws Exception {
        TestUser provider = registerProvider("Validators", "Asia/Tokyo");
        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/services")),
                        service("", 2, -1, "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(4)));
    }

    @Test
    void searchFiltersAndPaginates() throws Exception {
        TestUser spa = registerProvider("City Spa", "America/New_York");
        TestUser barber = registerProvider("Barber Bros", "America/New_York");
        createService(spa, "Facial", 45, 0, "60");
        createService(spa, "Massage", 60, 0, "90");
        createService(barber, "Beard Trim", 15, 0, "15");
        String inactive = createService(barber, "Hot Towel Shave", 30, 0, "35");
        mockMvc.perform(as(barber, delete("/api/v1/services/{id}", inactive))).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/services"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get("/api/v1/services").param("q", "MASS"))
                .andExpect(jsonPath("$.content[0].name").value("Massage"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/services").param("maxPrice", "60"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/services").param("maxDurationMinutes", "30"))
                .andExpect(jsonPath("$.content[0].name").value("Beard Trim"));
        mockMvc.perform(get("/api/v1/services").param("providerId", providerIdOf(spa)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/services").param("sort", "price,desc").param("size", "2"))
                .andExpect(jsonPath("$.content[0].name").value("Massage"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/providers/{id}/services", providerIdOf(barber)))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    private static Map<String, Object> service(String name, int duration, int buffer, String price) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", duration);
        body.put("bufferMinutes", buffer);
        body.put("price", new java.math.BigDecimal(price));
        return body;
    }
}
