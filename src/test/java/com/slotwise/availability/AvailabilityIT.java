package com.slotwise.availability;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AvailabilityIT extends AbstractApiIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("America/Toronto");

    private TestUser provider;
    private String providerId;
    private String serviceId;

    @BeforeEach
    void setUpProvider() throws Exception {
        provider = registerProvider("Clinic", ZONE.getId());
        providerId = providerIdOf(provider);
        serviceId = createService(provider, "Checkup", 30, 0, "40");
    }

    @Test
    void providerDefinesWeeklyScheduleWithDifferentDaysAndBreaks() throws Exception {
        Map<String, Object> monday = Map.of("dayOfWeek", "MONDAY", "startTime", "09:00", "endTime", "17:00",
                "breaks", List.of(Map.of("startTime", "12:00", "endTime", "13:00")));
        Map<String, Object> friday = Map.of("dayOfWeek", "FRIDAY", "startTime", "10:00", "endTime", "14:00");

        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me/availability")),
                        Map.of("days", List.of(friday, monday))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("America/Toronto"))
                .andExpect(jsonPath("$.days", hasSize(2)))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.days[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.days[0].breaks[0].startTime").value("12:00"))
                .andExpect(jsonPath("$.days[1].dayOfWeek").value("FRIDAY"));

        // replacing the schedule removes days that are no longer listed
        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me/availability")),
                        Map.of("days", List.of(friday))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(1)));

        mockMvc.perform(get("/api/v1/providers/{id}/availability", providerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklySchedule.days", hasSize(1)))
                .andExpect(jsonPath("$.weeklySchedule.days[0].dayOfWeek").value("FRIDAY"));
    }

    @Test
    void invalidSchedulesAreRejected() throws Exception {
        Map<String, Object> inverted = Map.of("dayOfWeek", "MONDAY", "startTime", "17:00", "endTime", "09:00");
        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me/availability")),
                        Map.of("days", List.of(inverted))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> badBreak = Map.of("dayOfWeek", "MONDAY", "startTime", "09:00", "endTime", "12:00",
                "breaks", List.of(Map.of("startTime", "11:00", "endTime", "13:00")));
        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me/availability")),
                        Map.of("days", List.of(badBreak))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));

        Map<String, Object> dup = Map.of("dayOfWeek", "MONDAY", "startTime", "09:00", "endTime", "12:00");
        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me/availability")),
                        Map.of("days", List.of(dup, dup))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));
    }

    @Test
    void slotsReflectScheduleBreaksAndServiceDuration() throws Exception {
        setDailySchedule(provider, "09:00", "11:00", "10:00", "10:30");
        LocalDate date = futureDate(ZONE, DayOfWeek.WEDNESDAY);

        mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId)
                        .param("serviceId", serviceId)
                        .param("from", date.toString()).param("to", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("America/Toronto"))
                .andExpect(jsonPath("$.durationMinutes").value(30))
                // default slot interval is 15 minutes: 09:00 09:15 09:30 | break | 10:30
                .andExpect(jsonPath("$.slots", hasSize(4)))
                .andExpect(jsonPath("$.slots[0].localStart").value(date + "T09:00:00" + offset(date)))
                .andExpect(jsonPath("$.slots[3].localStart").value(date + "T10:30:00" + offset(date)))
                .andExpect(jsonPath("$.slots[*].localStart", not(hasItem(date + "T10:00:00" + offset(date)))));
    }

    @Test
    void holidaysAndPartialBlocksRemoveSlots() throws Exception {
        setDailySchedule(provider, "09:00", "10:00", null, null);
        LocalDate holiday = futureDate(ZONE, DayOfWeek.MONDAY);
        LocalDate blockedMorning = holiday.plusDays(1);

        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/availability/exceptions")),
                        Map.of("date", holiday.toString(), "type", "HOLIDAY", "reason", "Family event")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("Family event"));
        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/availability/exceptions")),
                        Map.of("date", blockedMorning.toString(), "type", "BLOCKED", "startTime", "09:00",
                                "endTime", "09:30")))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId)
                        .param("serviceId", serviceId)
                        .param("from", holiday.toString()).param("to", blockedMorning.toString()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> starts = read(result, "$.slots[*].localStart");
        org.assertj.core.api.Assertions.assertThat(starts).containsExactly(
                blockedMorning + "T09:30:00" + offset(blockedMorning));

        // the public view shows the exception, but not the private reason
        mockMvc.perform(get("/api/v1/providers/{id}/availability", providerId)
                        .param("from", holiday.toString()).param("to", holiday.toString()))
                .andExpect(jsonPath("$.exceptions", hasSize(1)))
                .andExpect(jsonPath("$.exceptions[0].type").value("HOLIDAY"))
                .andExpect(jsonPath("$.exceptions[0].reason").doesNotExist());
    }

    @Test
    void extraHoursAddAvailabilityOnADayOff() throws Exception {
        LocalDate date = futureDate(ZONE, DayOfWeek.SUNDAY);
        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/availability/exceptions")),
                        Map.of("date", date.toString(), "type", "EXTRA_HOURS", "startTime", "18:00",
                                "endTime", "19:00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId)
                        .param("serviceId", serviceId)
                        .param("from", date.toString()).param("to", date.toString()))
                .andExpect(jsonPath("$.slots", hasSize(3)));
    }

    @Test
    void exceptionValidationAndOwnership() throws Exception {
        LocalDate date = futureDate(ZONE, DayOfWeek.MONDAY);
        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/availability/exceptions")),
                        Map.of("date", date.toString(), "type", "HOLIDAY", "startTime", "09:00", "endTime", "10:00")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/availability/exceptions")),
                        Map.of("date", date.toString(), "type", "EXTRA_HOURS")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/availability/exceptions")),
                        Map.of("date", LocalDate.now(ZONE).minusDays(3).toString(), "type", "HOLIDAY")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EXCEPTION_IN_PAST"));

        MvcResult created = mockMvc.perform(withJson(as(provider,
                        post("/api/v1/providers/me/availability/exceptions")),
                        Map.of("date", date.toString(), "type", "BLOCKED")))
                .andExpect(status().isCreated())
                .andReturn();
        String exceptionId = read(created, "$.id");

        TestUser otherProvider = registerProvider("Other", "UTC");
        mockMvc.perform(as(otherProvider, delete("/api/v1/providers/me/availability/exceptions/{id}", exceptionId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(as(provider, delete("/api/v1/providers/me/availability/exceptions/{id}", exceptionId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(provider, get("/api/v1/providers/me/availability/exceptions")
                        .param("from", date.toString()).param("to", date.toString())))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void slotQueryValidation() throws Exception {
        LocalDate date = futureDate(ZONE, DayOfWeek.MONDAY);
        mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId).param("serviceId", serviceId)
                        .param("from", date.toString()).param("to", date.minusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId).param("serviceId", serviceId)
                        .param("from", date.toString()).param("to", date.plusDays(60).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId))
                .andExpect(status().isBadRequest());

        TestUser other = registerProvider("Elsewhere", "UTC");
        String foreignService = createService(other, "Foreign", 30, 0, "10");
        mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId).param("serviceId", foreignService))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_OFFERED"));
    }

    @Test
    void pastDatesHaveNoSlots() throws Exception {
        setDailySchedule(provider, "00:00", "23:00", null, null);
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId).param("serviceId", serviceId)
                        .param("from", yesterday.toString()).param("to", yesterday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(0)));
    }

    private static String offset(LocalDate date) {
        return date.atTime(9, 0).atZone(ZONE).getOffset().getId();
    }
}
