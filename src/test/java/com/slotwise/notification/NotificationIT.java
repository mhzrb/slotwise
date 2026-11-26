package com.slotwise.notification;

import com.slotwise.notification.service.EmailMessage;
import com.slotwise.notification.service.EmailSender;
import com.slotwise.notification.service.NotificationDispatcher;
import com.slotwise.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationIT extends AbstractApiIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    @MockitoBean
    EmailSender emailSender;

    @Autowired
    NotificationDispatcher dispatcher;

    private TestUser provider;
    private TestUser customer;
    private String serviceId;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        provider = registerProvider("Salon", ZONE.getId());
        customer = registerCustomer("Nina");
        serviceId = createService(provider, "Colour", 60, 0, "90");
        setDailySchedule(provider, "09:00", "17:00", null, null);
        day = futureDate(ZONE, DayOfWeek.THURSDAY);
    }

    @Test
    void bookingWritesOutboxRowsForCustomerAndProviderInTheSameTransaction() throws Exception {
        String id = book(customer, at("10:00"));

        assertThat(notificationTypes(id)).containsExactlyInAnyOrder("APPOINTMENT_CREATED",
                "APPOINTMENT_REQUEST_RECEIVED");
        assertThat(count("status = 'PENDING'")).isEqualTo(2);
    }

    @Test
    void failedBookingCreatesNoNotifications() throws Exception {
        book(customer, at("10:00"));
        TestUser other = registerCustomer("Olga");
        mockMvc.perform(withJson(as(other, post("/api/v1/appointments")),
                        Map.of("serviceId", serviceId, "startAt", at("10:00").toString())))
                .andExpect(status().isConflict());

        assertThat(count("true")).isEqualTo(2); // only those of the first booking
    }

    @Test
    void lifecycleEventsNotifyTheRightPeople() throws Exception {
        String id = book(customer, at("11:00"));
        mockMvc.perform(as(provider, patch("/api/v1/appointments/{id}/confirm", id))).andExpect(status().isOk());
        mockMvc.perform(withJson(as(customer, patch("/api/v1/appointments/{id}/reschedule", id)),
                Map.of("startAt", at("14:00").toString()))).andExpect(status().isOk());
        mockMvc.perform(as(customer, patch("/api/v1/appointments/{id}/cancel", id))).andExpect(status().isOk());

        // rows written in one transaction can share a timestamp, so compare as a multiset
        assertThat(notificationTypes(id)).containsExactlyInAnyOrder(
                "APPOINTMENT_CREATED", "APPOINTMENT_REQUEST_RECEIVED", // to customer, to provider
                "APPOINTMENT_CONFIRMED",                               // to customer
                "APPOINTMENT_RESCHEDULED", "APPOINTMENT_RESCHEDULED",  // to both
                "APPOINTMENT_CANCELLED");                              // to provider (customer cancelled)
    }

    @Test
    void dispatcherDeliversAndUsersSeeOnlyTheirOwnNotifications() throws Exception {
        book(customer, at("10:00"));

        assertThat(dispatcher.dispatchDue()).isEqualTo(2);
        verify(emailSender, times(2)).send(any(EmailMessage.class));
        assertThat(count("status = 'SENT' AND sent_at IS NOT NULL")).isEqualTo(2);
        assertThat(dispatcher.dispatchDue()).isZero(); // nothing left

        mockMvc.perform(as(customer, get("/api/v1/notifications/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].type").value("APPOINTMENT_CREATED"))
                .andExpect(jsonPath("$.content[0].status").value("SENT"));
        mockMvc.perform(as(provider, get("/api/v1/notifications/me")))
                .andExpect(jsonPath("$.content[0].type").value("APPOINTMENT_REQUEST_RECEIVED"));
    }

    @Test
    void deliveryFailuresNeverAffectTheAppointmentAndAreRetriedThenFailed() throws Exception {
        doThrow(new IllegalStateException("SMTP unavailable")).when(emailSender).send(any());

        String id = book(customer, at("12:00"));
        dispatcher.dispatchDue();

        // the booking is intact
        mockMvc.perform(as(customer, get("/api/v1/appointments/{id}", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(count("status = 'PENDING' AND attempts = 1 AND last_error = 'SMTP unavailable' "
                + "AND next_attempt_at > now()")).isEqualTo(2);

        // not due yet -> not retried
        assertThat(dispatcher.dispatchDue()).isZero();

        // exhaust remaining attempts (max 5), fast-forwarding the backoff each time
        for (int attempt = 2; attempt <= 5; attempt++) {
            jdbcTemplate.update("UPDATE notifications SET next_attempt_at = now() - interval '1 second'");
            assertThat(dispatcher.dispatchDue()).isEqualTo(2);
        }
        assertThat(count("status = 'FAILED' AND attempts = 5")).isEqualTo(2);
        jdbcTemplate.update("UPDATE notifications SET next_attempt_at = now() - interval '1 second'");
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void parallelDispatchersDeliverEachNotificationExactlyOnce() throws Exception {
        for (int hour = 9; hour < 17; hour++) {
            book(customer, at(hour + ":00")); // 8 bookings x 2 notifications = 16
        }
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            delivered.add(((EmailMessage) inv.getArgument(0)).subject() + "|" + ((EmailMessage) inv.getArgument(0)).to());
            Thread.sleep(20); // widen the window for overlap
            return null;
        }).when(emailSender).send(any());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                int total = 0;
                int n;
                while ((n = dispatcher.dispatchDue()) > 0) {
                    total += n;
                }
                return total;
            }));
        }
        go.countDown();
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(total).isEqualTo(16);
        assertThat(delivered).hasSize(16);
        assertThat(count("status = 'SENT' AND attempts = 1")).isEqualTo(16);
    }

    // ---------------------------------------------------------------- helpers

    private String book(TestUser user, Instant start) throws Exception {
        MvcResult result = mockMvc.perform(withJson(as(user, post("/api/v1/appointments")),
                        Map.of("serviceId", serviceId, "startAt", start.toString())))
                .andExpect(status().isCreated()).andReturn();
        return read(result, "$.id");
    }

    private List<String> notificationTypes(String appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM notifications WHERE appointment_id = ?::uuid ORDER BY created_at",
                String.class, appointmentId);
    }

    private int count(String where) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE " + where, Integer.class);
    }

    private Instant at(String time) {
        String padded = time.length() == 4 ? "0" + time : time;
        return day.atTime(java.time.LocalTime.parse(padded)).atZone(ZONE).toInstant();
    }
}
