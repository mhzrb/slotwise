package com.slotwise.provider;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderIT extends AbstractApiIntegrationTest {

    @Test
    void providerRegistrationCreatesProfile() throws Exception {
        TestUser provider = registerProvider("Dental Care", "America/Vancouver");

        mockMvc.perform(as(provider, get("/api/v1/providers/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Dental Care"))
                .andExpect(jsonPath("$.timeZone").value("America/Vancouver"))
                .andExpect(jsonPath("$.slotIntervalMinutes").value(15))
                .andExpect(jsonPath("$.userId").value(provider.id().toString()));
    }

    @Test
    void publicProviderViewExposesNoPrivateData() throws Exception {
        TestUser provider = registerProvider("Physio Plus", "Europe/Madrid");
        String id = providerIdOf(provider);

        mockMvc.perform(get("/api/v1/providers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Physio Plus"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.active").doesNotExist());
    }

    @Test
    void customerCannotUseProviderSelfServiceEndpoints() throws Exception {
        TestUser customer = registerCustomer("Quinn");
        mockMvc.perform(as(customer, get("/api/v1/providers/me"))).andExpect(status().isForbidden());
        mockMvc.perform(as(customer, get("/api/v1/providers/me/appointments"))).andExpect(status().isForbidden());
    }

    @Test
    void providerUpdatesProfileAndInactiveProvidersAreHidden() throws Exception {
        TestUser provider = registerProvider("Tutor Hub", "Europe/Rome");
        String id = providerIdOf(provider);

        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me")),
                        Map.of("businessName", "Tutor Hub", "timeZone", "Mars/Olympus")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("timeZone"));

        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me")),
                        Map.of("businessName", "Tutor Hub Pro", "timeZone", "Europe/Rome",
                                "slotIntervalMinutes", 30, "active", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotIntervalMinutes").value(30))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/providers/{id}", id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/providers")).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchProvidersByName() throws Exception {
        registerProvider("Sunrise Yoga", "Australia/Sydney");
        registerProvider("Sunset Pilates", "Australia/Sydney");
        registerProvider("Moon Clinic", "Australia/Sydney");

        mockMvc.perform(get("/api/v1/providers").param("q", "sun"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].businessName").value("Sunrise Yoga"));
        mockMvc.perform(get("/api/v1/providers").param("q", "100%_"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/providers").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void unknownProviderReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/providers/{id}", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/providers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
}
