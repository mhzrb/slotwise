package com.slotwise.appointment;

import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppointmentLifecycleIT extends AbstractApiIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("America/Toronto");

    private TestUser provider;
    private TestUser customer;
    private String providerId;
    private String serviceId;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        provider = registerProvider("Wellness Studio", ZONE.getId());
        customer = registerCustomer("Rita");
        providerId = providerIdOf(provider);
        serviceId = createService(provider, "Massage", 60, 15, "75.00");
        setDailySchedule(provider, "09:00", "17:00", "12:00", "13:00");
        day = futureDate(ZONE, DayOfWeek.WEDNESDAY);
    }

    @Test
    void customerBooksAndSlotDisappears() throws Exception {
        Instant start = at(day, "09:00");

        book(customer, start)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.startAt").value(start.toString()))
                .andExpect(jsonPath("$.endAt").value(start.plus(60, ChronoUnit.MINUTES).toString()))
                .andExpect(jsonPath("$.timeZone").value("America/Toronto"))
                .andExpect(jsonPath("$.service.name").value("Massage"))
                .andExpect(jsonPath("$.provider.businessName").value("Wellness Studio"))
                .andExpect(jsonPath("$.price").value(75.00));

        List<String> starts = slotStarts(day);
        // booked range incl. 15 min buffer is [09:00, 10:15): next 60-min slot starts at 10:15
        assertThat(starts).contains(at(day, "10:15").toString(), at(day, "11:00").toString())
                .doesNotContain(at(day, "09:00").toString(), at(day, "09:45").toString(), at(day, "10:00").toString())
                // 11:15 would run into the 12:00 break
                .doesNotContain(at(day, "11:15").toString());
    }

    @Test
    void bookingSameSlotTwiceReturns409() throws Exception {
        Instant start = at(day, "14:00");
        book(customer, start).andExpect(status().isCreated());

        TestUser other = registerCustomer("Sam");
        book(other, start)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
        book(other, at(day, "14:30"))
                .andExpect(status().isConflict());
    }

    @Test
    void bookingRulesAreEnforced() throws Exception {
        book(customer, at(LocalDate.now(ZONE).minusDays(2), "10:00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BOOKING_IN_PAST"));
        book(customer, at(day, "10:05"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SLOT_NOT_OFFERED"));
        book(customer, at(day, "12:30")) // during break
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SLOT_NOT_OFFERED"));
        book(customer, at(day, "16:30")) // would end after closing
                .andExpect(status().isUnprocessableContent());
        book(customer, at(LocalDate.now(ZONE).plusDays(200), "10:00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BOOKING_TOO_FAR_AHEAD"));

        mockMvc.perform(withJson(as(customer, post("/api/v1/appointments")),
                        Map.of("serviceId", UUID.randomUUID().toString(), "startAt", at(day, "10:00").toString())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withJson(as(customer, post("/api/v1/appointments")), Map.of("serviceId", serviceId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startAt"));
    }

    @Test
    void onlyCustomersCanBookAndInactiveServicesAreNotBookable() throws Exception {
        book(provider, at(day, "10:00")).andExpect(status().isForbidden());

        mockMvc.perform(as(provider, delete("/api/v1/services/{id}", serviceId))).andExpect(status().isNoContent());
        book(customer, at(day, "10:00")).andExpect(status().isNotFound());
    }

    @Test
    void servicesOfDisabledProviderAccountsAreNotBookable() throws Exception {
        TestUser admin = createAdmin();
        mockMvc.perform(withJson(as(admin, patch("/api/v1/admin/users/{id}/status", provider.id())),
                Map.of("enabled", false))).andExpect(status().isOk());

        book(customer, at(day, "10:00"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/services/{id}", serviceId)).andExpect(status().isNotFound());
    }

    @Test
    void appointmentIsVisibleOnlyToParticipantsAndAdmins() throws Exception {
        String id = bookedId(customer, at(day, "09:00"));
        TestUser stranger = registerCustomer("Stranger");
        TestUser otherProvider = registerProvider("Elsewhere", "UTC");
        TestUser admin = createAdmin();

        mockMvc.perform(as(customer, get("/api/v1/appointments/{id}", id))).andExpect(status().isOk());
        mockMvc.perform(as(provider, get("/api/v1/appointments/{id}", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.fullName").value("Rita"));
        mockMvc.perform(as(admin, get("/api/v1/appointments/{id}", id))).andExpect(status().isOk());
        mockMvc.perform(as(stranger, get("/api/v1/appointments/{id}", id))).andExpect(status().isForbidden());
        mockMvc.perform(as(otherProvider, get("/api/v1/appointments/{id}", id))).andExpect(status().isForbidden());

        // and cannot be manipulated by strangers
        mockMvc.perform(as(stranger, patch("/api/v1/appointments/{id}/cancel", id))).andExpect(status().isForbidden());
        mockMvc.perform(withJson(as(stranger, patch("/api/v1/appointments/{id}/reschedule", id)),
                Map.of("startAt", at(day, "15:00").toString()))).andExpect(status().isForbidden());
        mockMvc.perform(as(otherProvider, patch("/api/v1/appointments/{id}/confirm", id)))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(customer, patch("/api/v1/appointments/{id}/confirm", id)))
                .andExpect(status().isForbidden());
    }

    @Test
    void providerConfirmsThenCompletesAfterStart() throws Exception {
        String id = bookedId(customer, at(day, "09:00"));

        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/confirm", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/confirm", id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/complete", id)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_STARTED"));

        movesIntoThePast(id);
        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/complete", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/cancel", id)))
                .andExpect(status().isConflict());
    }

    @Test
    void providerRejectsPendingRequestAndSlotIsFreed() throws Exception {
        Instant start = at(day, "15:00");
        String id = bookedId(customer, start);

        mockMvc.perform(withJson(as(provider, patch("/api/v1/appointments/{id}/reject", id)),
                        Map.of("reason", "Fully booked")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.cancellationReason").value("Fully booked"));
        assertThat(slotStarts(day)).contains(start.toString());

        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/confirm", id)))
                .andExpect(status().isConflict());
    }

    @Test
    void customerCancelsAndCancellationCutoffApplies() throws Exception {
        String id = bookedId(customer, at(day, "11:00"));
        mockMvc.perform(withJson(as(customer, patch("/api/v1/appointments/{id}/cancel", id)),
                        Map.of("reason", "Change of plans")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("CUSTOMER"));
        assertThat(slotStarts(day)).contains(at(day, "11:00").toString());

        // an appointment starting within the 24h cutoff
        String soonId = bookedId(customer, firstSlotWithin24Hours());
        mockMvc.perform(as(customer, patch("/api/v1/appointments/{id}/cancel", soonId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
        // the provider can still cancel it
        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/cancel", soonId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledBy").value("PROVIDER"));
    }

    @Test
    void customerReschedulesWithinRules() throws Exception {
        String id = bookedId(customer, at(day, "09:00"));
        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/confirm", id))).andExpect(status().isOk());

        // moving by 30 minutes overlaps the appointment's own old range: allowed
        reschedule(customer, id, at(day, "09:30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.rescheduleCount").value(1))
                .andExpect(jsonPath("$.startAt").value(at(day, "09:30").toString()));
        assertThat(slotStarts(day)).doesNotContain(at(day, "09:30").toString());

        // cannot move onto another customer's appointment
        TestUser other = registerCustomer("Tom");
        bookedId(other, at(day, "14:00"));
        reschedule(customer, id, at(day, "14:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));

        reschedule(customer, id, at(day, "15:15")).andExpect(status().isOk());
        reschedule(customer, id, at(day, "09:00")).andExpect(status().isOk());
        reschedule(customer, id, at(day, "10:00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESCHEDULE_LIMIT_REACHED"));
    }

    @Test
    void customerHistoryIsPaginatedAndFiltered() throws Exception {
        String first = bookedId(customer, at(day, "09:00"));
        bookedId(customer, at(day.plusDays(1), "09:00"));
        bookedId(customer, at(day.plusDays(2), "09:00"));
        mockMvc.perform(as(customer, patch("/api/v1/appointments/{id}/cancel", first))).andExpect(status().isOk());
        bookedId(registerCustomer("Uma"), at(day, "14:00"));

        mockMvc.perform(as(customer, get("/api/v1/appointments/me")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].startAt").value(at(day.plusDays(2), "09:00").toString()));
        mockMvc.perform(as(customer, get("/api/v1/appointments/me").param("status", "CANCELLED")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(as(customer, get("/api/v1/appointments/me")
                        .param("from", at(day.plusDays(1), "00:00").toString())
                        .param("size", "1").param("sort", "startAt,asc")))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].startAt").value(at(day.plusDays(1), "09:00").toString()));
    }

    @Test
    void providerSeesOwnAppointmentsAndDailyWeeklySchedule() throws Exception {
        LocalDate monday = futureDate(ZONE, DayOfWeek.MONDAY);
        bookedId(customer, at(monday, "09:00"));
        String cancelled = bookedId(customer, at(monday, "14:00"));
        mockMvc.perform(as(customer, patch("/api/v1/appointments/{id}/cancel", cancelled))).andExpect(status().isOk());
        bookedId(registerCustomer("Vic"), at(monday.plusDays(3), "10:00"));

        TestUser otherProvider = registerProvider("Other", ZONE.getId());
        String otherService = createService(otherProvider, "Other", 30, 0, "10");
        setDailySchedule(otherProvider, "09:00", "17:00", null, null);
        mockMvc.perform(withJson(as(customer, post("/api/v1/appointments")),
                Map.of("serviceId", otherService, "startAt", at(monday, "09:00").toString())))
                .andExpect(status().isCreated());

        mockMvc.perform(as(provider, get("/api/v1/providers/me/appointments")))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(as(provider, get("/api/v1/providers/me/appointments").param("status", "PENDING")))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(as(provider, get("/api/v1/providers/me/schedule").param("date", monday.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view").value("DAY"))
                .andExpect(jsonPath("$.days", hasSize(1)))
                .andExpect(jsonPath("$.totalAppointments").value(1)); // cancelled one excluded

        mockMvc.perform(as(provider, get("/api/v1/providers/me/schedule")
                        .param("date", monday.plusDays(2).toString()).param("view", "WEEK")))
                .andExpect(jsonPath("$.from").value(monday.toString()))
                .andExpect(jsonPath("$.to").value(monday.plusDays(6).toString()))
                .andExpect(jsonPath("$.days", hasSize(7)))
                .andExpect(jsonPath("$.totalAppointments").value(2))
                .andExpect(jsonPath("$.days[3].appointments[0].customer.fullName").value("Vic"));
    }

    @Test
    void adminCanSearchAllAppointmentsAndCancelAny() throws Exception {
        TestUser admin = createAdmin();
        String id = bookedId(customer, at(day, "10:00"));

        mockMvc.perform(as(admin, get("/api/v1/admin/appointments").param("providerId", providerId)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(as(customer, get("/api/v1/admin/appointments"))).andExpect(status().isForbidden());

        mockMvc.perform(as(admin, patch("/api/v1/appointments/{id}/cancel", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledBy").value("ADMIN"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions book(TestUser user, Instant start) throws Exception {
        return mockMvc.perform(withJson(as(user, post("/api/v1/appointments")),
                Map.of("serviceId", serviceId, "startAt", start.toString(), "notes", "first visit")));
    }

    private String bookedId(TestUser user, Instant start) throws Exception {
        return read(book(user, start).andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private ResultActions reschedule(TestUser user, String id, Instant start) throws Exception {
        return mockMvc.perform(withJson(as(user, patch("/api/v1/appointments/{id}/reschedule", id)),
                Map.of("startAt", start.toString())));
    }

    private List<String> slotStarts(LocalDate date) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId)
                        .param("serviceId", serviceId).param("from", date.toString()).param("to", date.toString()))
                .andExpect(status().isOk()).andReturn();
        return read(result, "$.slots[*].startAt");
    }

    private Instant firstSlotWithin24Hours() throws Exception {
        setDailySchedule(provider, "00:00", "23:59", null, null);
        LocalDate today = LocalDate.now(ZONE);
        MvcResult result = mockMvc.perform(get("/api/v1/providers/{id}/slots", providerId)
                        .param("serviceId", serviceId)
                        .param("from", today.toString()).param("to", today.plusDays(1).toString()))
                .andExpect(status().isOk()).andReturn();
        Instant first = Instant.parse(read(result, "$.slots[0].startAt"));
        assertThat(first).isBefore(Instant.now().plus(24, ChronoUnit.HOURS));
        return first;
    }

    /** Simulates the passage of time by moving the appointment two hours into the past. */
    private void movesIntoThePast(String id) {
        jdbcTemplate.update("""
                UPDATE appointments SET start_at = now() - interval '2 hours',
                    end_at = now() - interval '1 hour', blocked_until = now() - interval '45 minutes'
                WHERE id = ?::uuid""", id);
    }

    private static Instant at(LocalDate date, String time) {
        return date.atTime(java.time.LocalTime.parse(time)).atZone(ZONE).toInstant();
    }
}
