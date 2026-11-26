package com.slotwise.concurrency;

import com.slotwise.support.AbstractIntegrationTest;
import com.slotwise.support.HttpTestClient;
import com.slotwise.support.HttpTestClient.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests against a real HTTP server (random port), real PostgreSQL
 * and genuinely parallel requests. A start gate (latch) releases all threads
 * at once so the requests race inside the booking transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentBookingIT extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final String PASSWORD = "Secret123";

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    private HttpTestClient api;
    private LocalDate day;

    @BeforeEach
    void setUpClient() {
        api = new HttpTestClient(port, objectMapper);
        day = LocalDate.now(ZONE).plusDays(14).with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
    }

    /** The core guarantee: N customers, one slot, exactly one booking. Repeated to shake out flakiness. */
    @RepeatedTest(5)
    void manyCustomersBookingTheSameSlotSimultaneously_exactlyOneSucceeds() throws Exception {
        Provider provider = newProvider("Same Slot Clinic", 60, 0);
        List<Session> customers = newCustomers(12);
        Instant start = at(day, "10:00");

        List<HttpResponse<String>> responses = race(customers.stream()
                .<Callable<HttpResponse<String>>>map(c -> () -> book(c, provider.serviceId(), start))
                .toList());

        assertThat(statusCounts(responses)).containsEntry(201, 1).containsEntry(409, 11).hasSize(2);
        responses.stream().filter(r -> r.statusCode() == 409).forEach(r ->
                assertThat(HttpTestClient.<String>read(r, "$.code")).isEqualTo("SLOT_UNAVAILABLE"));
        assertThat(activeAppointments(provider.providerId())).isEqualTo(1);
        assertNoOverlapsInDatabase();
    }

    @Test
    void overlappingRequestsWithDifferentStartTimes_exactlyOneSucceeds() throws Exception {
        // 60-minute service: every pair of these starts overlaps
        Provider provider = newProvider("Overlap Clinic", 60, 0);
        List<String> starts = List.of("10:00", "10:15", "10:30", "10:45", "10:00", "10:15", "10:30", "10:45");
        List<Session> customers = newCustomers(starts.size());

        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            Session customer = customers.get(i);
            Instant start = at(day, starts.get(i));
            tasks.add(() -> book(customer, provider.serviceId(), start));
        }
        List<HttpResponse<String>> responses = race(tasks);

        assertThat(statusCounts(responses)).containsEntry(201, 1).containsEntry(409, starts.size() - 1);
        assertThat(activeAppointments(provider.providerId())).isEqualTo(1);
        assertNoOverlapsInDatabase();
    }

    @Test
    void bufferTimeIsRespectedUnderConcurrency() throws Exception {
        // 30 min + 30 min buffer: 10:00 occupies [10:00, 11:00), so 10:30 conflicts, 11:00 does not
        Provider provider = newProvider("Buffer Clinic", 30, 30);
        List<Session> customers = newCustomers(6);
        List<String> starts = List.of("10:00", "10:30", "10:00", "10:30", "10:00", "10:30");

        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            Session customer = customers.get(i);
            Instant start = at(day, starts.get(i));
            tasks.add(() -> book(customer, provider.serviceId(), start));
        }
        List<HttpResponse<String>> responses = race(tasks);

        assertThat(statusCounts(responses)).containsEntry(201, 1);
        assertNoOverlapsInDatabase();
    }

    @Test
    void simultaneousNonOverlappingBookingsForTheSameProvider_allSucceed() throws Exception {
        // The provider lock serialises requests but must not reject valid, disjoint bookings.
        Provider provider = newProvider("Busy Clinic", 60, 0);
        List<Session> customers = newCustomers(8);

        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < customers.size(); i++) {
            Session customer = customers.get(i);
            Instant start = at(day, LocalTime.of(9 + i, 0).toString());
            tasks.add(() -> book(customer, provider.serviceId(), start));
        }
        List<HttpResponse<String>> responses = race(tasks);

        assertThat(statusCounts(responses)).containsEntry(201, 8).hasSize(1);
        assertThat(activeAppointments(provider.providerId())).isEqualTo(8);
    }

    @Test
    void contentionIsIsolatedPerProvider() throws Exception {
        // Two contenders per provider, all racing at once: exactly one winner per provider.
        int providerCount = 4;
        List<Provider> providers = new ArrayList<>();
        for (int i = 0; i < providerCount; i++) {
            providers.add(newProvider("Provider " + i, 60, 0));
        }
        List<Session> customers = newCustomers(providerCount * 2);
        Instant start = at(day, "11:00");

        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < customers.size(); i++) {
            Session customer = customers.get(i);
            Provider provider = providers.get(i % providerCount);
            tasks.add(() -> book(customer, provider.serviceId(), start));
        }
        List<HttpResponse<String>> responses = race(tasks);

        assertThat(statusCounts(responses)).containsEntry(201, providerCount).containsEntry(409, providerCount);
        for (Provider provider : providers) {
            assertThat(activeAppointments(provider.providerId())).isEqualTo(1);
        }
    }

    @Test
    void rescheduleAndNewBookingRacingForTheSameSlot_exactlyOneWins() throws Exception {
        Provider provider = newProvider("Reschedule Clinic", 60, 0);
        List<Session> customers = newCustomers(2);
        Session rescheduler = customers.get(0);
        Session booker = customers.get(1);
        HttpResponse<String> existing = book(rescheduler, provider.serviceId(), at(day, "09:00"));
        assertThat(existing.statusCode()).isEqualTo(201);
        String appointmentId = HttpTestClient.read(existing, "$.id");
        Instant target = at(day, "15:00");

        List<HttpResponse<String>> responses = race(List.of(
                () -> api.patch("/api/v1/appointments/" + appointmentId + "/reschedule", rescheduler.accessToken(),
                        Map.of("startAt", target.toString())),
                () -> book(booker, provider.serviceId(), target)));

        long successes = responses.stream().filter(r -> r.statusCode() / 100 == 2).count();
        assertThat(successes).isEqualTo(1);
        assertThat(responses).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(409));
        assertNoOverlapsInDatabase();
    }

    @Test
    void concurrentConfirmAndRejectOfTheSameAppointment_exactlyOneApplies() throws Exception {
        Provider provider = newProvider("Decision Clinic", 60, 0);
        Session customer = newCustomers(1).getFirst();
        HttpResponse<String> booked = book(customer, provider.serviceId(), at(day, "13:00"));
        String id = HttpTestClient.read(booked, "$.id");

        List<HttpResponse<String>> responses = race(List.of(
                () -> api.patch("/api/v1/appointments/" + id + "/confirm", provider.session().accessToken(), null),
                () -> api.patch("/api/v1/appointments/" + id + "/reject", provider.session().accessToken(),
                        Map.of("reason", "x"))));

        // one wins; the loser gets 409 either from optimistic locking or from the state machine
        assertThat(statusCounts(responses)).containsEntry(200, 1).containsEntry(409, 1);
        String finalStatus = jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid",
                String.class, id);
        assertThat(finalStatus).isIn("CONFIRMED", "REJECTED");
    }

    /**
     * Defence in depth: bypass the application entirely and race raw inserts
     * in separate transactions. The PostgreSQL exclusion constraint alone
     * must let exactly one overlapping row commit.
     * <p>
     * Losers are rejected with an exclusion violation (23P01), or - when
     * inserts wait on each other's uncommitted tuples - with a deadlock abort
     * (40P01) or a lock timeout (55P03). All preserve the invariant; the latter
     * two are why the application path takes the provider lock first.
     */
    @RepeatedTest(3)
    void databaseConstraintAloneRejectsConcurrentOverlappingInserts() throws Exception {
        Provider provider = newProvider("Raw Insert Clinic", 60, 0);
        Session customer = newCustomers(1).getFirst();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Instant base = at(day, "10:00");
        List<String> rejections = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Callable<Boolean>> inserts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Instant start = base.plus(i * 5L, ChronoUnit.MINUTES); // all within the same hour
            inserts.add(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        insertRawAppointment(provider, customer.userId(), start);
                        sleep(200); // keep the transaction open so the others really overlap with it
                    });
                    return true;
                } catch (DataAccessException ex) {
                    rejections.add(sqlState(ex));
                    return false;
                }
            });
        }
        List<Boolean> results = race(inserts);

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        // 23P01 exclusion_violation, 40P01 deadlock_detected, 55P03 lock_not_available
        assertThat(rejections).hasSize(7).allSatisfy(state -> assertThat(state).isIn("23P01", "40P01", "55P03"));
        assertNoOverlapsInDatabase();
    }

    /**
     * Lock waits are bounded by PostgreSQL's lock_timeout (2s in tests): when
     * the provider row stays locked by another transaction, the booking fails
     * fast with 503 + Retry-After instead of tying up a connection forever.
     */
    @Test
    void bookingFailsFastWhenProviderLockCannotBeAcquired() throws Exception {
        Provider provider = newProvider("Locked Clinic", 60, 0);
        Session customer = newCustomers(1).getFirst();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);

        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = holder.submit(() -> tx.executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT id FROM provider_profiles WHERE id = ?::uuid FOR UPDATE",
                    provider.providerId());
            locked.countDown();
            sleep(4_000);
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long startNanos = System.nanoTime();
            HttpResponse<String> response = book(customer, provider.serviceId(), at(day, "10:00"));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(HttpTestClient.<String>read(response, "$.code")).isEqualTo("LOCK_TIMEOUT");
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
            assertThat(elapsedMs).isLessThan(3_900);
        } finally {
            lockHolder.get(10, TimeUnit.SECONDS);
            holder.shutdownNow();
        }
        // once the lock is released, the same booking succeeds
        assertThat(book(customer, provider.serviceId(), at(day, "10:00")).statusCode()).isEqualTo(201);
    }

    // ================================================================== fixtures & helpers

    private record Provider(Session session, String providerId, String serviceId) {
    }

    private Provider newProvider(String name, int durationMinutes, int bufferMinutes) {
        Session session = api.register(Map.of(
                "email", "p-" + UUID.randomUUID() + "@example.com", "password", PASSWORD,
                "fullName", name, "role", "PROVIDER", "businessName", name, "timeZone", ZONE.getId()));
        String providerId = HttpTestClient.read(api.get("/api/v1/providers/me", session.accessToken()), "$.id");
        HttpResponse<String> service = api.post("/api/v1/providers/me/services", session.accessToken(), Map.of(
                "name", "Service", "durationMinutes", durationMinutes, "bufferMinutes", bufferMinutes,
                "price", 50));
        assertThat(service.statusCode()).isEqualTo(201);

        List<Map<String, Object>> days = new ArrayList<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            Map<String, Object> d = new HashMap<>();
            d.put("dayOfWeek", dow.name());
            d.put("startTime", "08:00");
            d.put("endTime", "18:00");
            days.add(d);
        }
        HttpResponse<String> schedule = api.send("PUT", "/api/v1/providers/me/availability", session.accessToken(),
                Map.of("days", days));
        assertThat(schedule.statusCode()).isEqualTo(200);
        return new Provider(session, providerId, HttpTestClient.read(service, "$.id"));
    }

    private List<Session> newCustomers(int count) {
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sessions.add(api.register(Map.of("email", "c-" + UUID.randomUUID() + "@example.com",
                    "password", PASSWORD, "fullName", "Customer " + i)));
        }
        return sessions;
    }

    private HttpResponse<String> book(Session customer, String serviceId, Instant start) {
        return api.post("/api/v1/appointments", customer.accessToken(),
                Map.of("serviceId", serviceId, "startAt", start.toString()));
    }

    /** Runs all tasks on their own thread, released simultaneously by a start gate. */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<Integer, Integer> statusCounts(List<HttpResponse<String>> responses) {
        Map<Integer, Integer> counts = new HashMap<>();
        responses.forEach(r -> counts.merge(r.statusCode(), 1, Integer::sum));
        return counts;
    }

    private int activeAppointments(String providerId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM appointments
                WHERE provider_id = ?::uuid AND status IN ('PENDING', 'CONFIRMED')""", Integer.class, providerId);
    }

    /** Independent consistency check: no two active appointments of one provider overlap. */
    private void assertNoOverlapsInDatabase() {
        Integer overlaps = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM appointments a
                JOIN appointments b ON a.provider_id = b.provider_id AND a.id < b.id
                WHERE a.status IN ('PENDING', 'CONFIRMED') AND b.status IN ('PENDING', 'CONFIRMED')
                  AND tstzrange(a.start_at, a.blocked_until, '[)') && tstzrange(b.start_at, b.blocked_until, '[)')
                """, Integer.class);
        assertThat(overlaps).isZero();
    }

    private void insertRawAppointment(Provider provider, UUID customerId, Instant start) {
        Instant end = start.plus(60, ChronoUnit.MINUTES);
        jdbcTemplate.update("""
                INSERT INTO appointments (id, provider_id, customer_id, service_id, start_at, end_at, blocked_until,
                    status, price, created_at, updated_at)
                VALUES (?, ?::uuid, ?, ?::uuid, ?, ?, ?, 'PENDING', 50, now(), now())""",
                UUID.randomUUID(), provider.providerId(), customerId, provider.serviceId(),
                Timestamp.from(start), Timestamp.from(end), Timestamp.from(end));
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "unknown";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Instant at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
    }
}
