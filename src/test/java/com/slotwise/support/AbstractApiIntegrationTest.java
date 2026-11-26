package com.slotwise.support;

import com.jayway.jsonpath.JsonPath;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import com.slotwise.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Integration tests that drive the full HTTP stack (security, validation, JSON) through MockMvc. */
@AutoConfigureMockMvc
public abstract class AbstractApiIntegrationTest extends AbstractIntegrationTest {

    protected static final String PASSWORD = "Secret123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /** Identity + tokens of a user created through the public API. */
    public record TestUser(UUID id, String email, String accessToken, String refreshToken) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    protected TestUser registerCustomer(String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", uniqueEmail(name));
        body.put("password", PASSWORD);
        body.put("fullName", name);
        return register(body);
    }

    protected TestUser registerProvider(String businessName, String timeZone) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", uniqueEmail("provider"));
        body.put("password", PASSWORD);
        body.put("fullName", businessName + " Owner");
        body.put("role", "PROVIDER");
        body.put("businessName", businessName);
        body.put("timeZone", timeZone);
        return register(body);
    }

    protected TestUser register(Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return toTestUser(result, (String) body.get("email"));
    }

    protected TestUser createAdmin() throws Exception {
        String email = uniqueEmail("admin");
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Admin", null, Role.ADMIN));
        return login(email, PASSWORD);
    }

    protected TestUser login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return toTestUser(result, email);
    }

    protected MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, user.bearer());
    }

    protected MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    // ----- provider / scheduling fixtures

    protected String providerIdOf(TestUser provider) throws Exception {
        return read(mockMvc.perform(as(provider, get("/api/v1/providers/me")))
                .andExpect(status().isOk()).andReturn(), "$.id");
    }

    protected String createService(TestUser provider, String name, int durationMinutes, int bufferMinutes,
            String price) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", durationMinutes);
        body.put("bufferMinutes", bufferMinutes);
        body.put("price", new BigDecimal(price));
        MvcResult result = mockMvc.perform(withJson(as(provider, post("/api/v1/providers/me/services")), body))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "$.id");
    }

    /** Sets the same working hours (optionally with one break) for every day of the week. */
    protected void setDailySchedule(TestUser provider, String start, String end, String breakStart, String breakEnd)
            throws Exception {
        List<Map<String, Object>> days = new ArrayList<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            Map<String, Object> day = new HashMap<>();
            day.put("dayOfWeek", dow.name());
            day.put("startTime", start);
            day.put("endTime", end);
            day.put("breaks", breakStart == null ? List.of() : List.of(Map.of("startTime", breakStart,
                    "endTime", breakEnd)));
            days.add(day);
        }
        mockMvc.perform(withJson(as(provider, put("/api/v1/providers/me/availability")), Map.of("days", days)))
                .andExpect(status().isOk());
    }

    /** A date safely in the future (beyond notice periods, within the booking horizon). */
    protected static LocalDate futureDate(ZoneId zone, DayOfWeek dayOfWeek) {
        return LocalDate.now(zone).plusDays(14).with(TemporalAdjusters.nextOrSame(dayOfWeek));
    }

    protected String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    protected static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    protected static String uniqueEmail(String prefix) {
        return prefix.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
    }

    private TestUser toTestUser(MvcResult result, String email) throws Exception {
        String id = read(result, "$.user.id");
        return new TestUser(UUID.fromString(id), email, read(result, "$.accessToken"), read(result, "$.refreshToken"));
    }
}
