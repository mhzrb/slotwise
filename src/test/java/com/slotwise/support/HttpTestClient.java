package com.slotwise.support;

import com.jayway.jsonpath.JsonPath;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Minimal real-HTTP client for tests that need genuine concurrent requests against a running server. */
public class HttpTestClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public HttpTestClient(int port, ObjectMapper objectMapper) {
        this.baseUrl = "http://localhost:" + port;
        this.objectMapper = objectMapper;
    }

    public record Session(UUID userId, String accessToken) {
    }

    public HttpResponse<String> send(String method, String path, String accessToken, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        try {
            return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP request failed: " + method + " " + path, e);
        }
    }

    public HttpResponse<String> post(String path, String token, Object body) {
        return send("POST", path, token, body);
    }

    public HttpResponse<String> patch(String path, String token, Object body) {
        return send("PATCH", path, token, body);
    }

    public HttpResponse<String> get(String path, String token) {
        return send("GET", path, token, null);
    }

    public Session register(Map<String, Object> body) {
        HttpResponse<String> response = post("/api/v1/auth/register", null, body);
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Registration failed: " + response.statusCode() + " " + response.body());
        }
        return new Session(UUID.fromString(JsonPath.read(response.body(), "$.user.id")),
                JsonPath.read(response.body(), "$.accessToken"));
    }

    public static <T> T read(HttpResponse<String> response, String path) {
        return JsonPath.read(response.body(), path);
    }
}
