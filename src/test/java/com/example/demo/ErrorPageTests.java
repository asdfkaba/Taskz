package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** A browser gets the Taskz error page (static/error/4xx.html), API clients keep getting JSON. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRsaKeys.class)
class ErrorPageTests {

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aBrowserWithoutLoginSeesTheTaskzPage() throws Exception {
        HttpResponse<String> response = get("/attachments/0b7a0f1e-6c3d-4f5a-9e2b-7d1c8a4e5f60", "text/html,*/*;q=0.8");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("text/html"));
        assertThat(response.body()).contains("<title>Not available · Taskz</title>", "Open Taskz").doesNotContain("Whitelabel");
    }

    @Test
    void apiClientsStillGetJson() throws Exception {
        HttpResponse<String> response = get("/attachments/0b7a0f1e-6c3d-4f5a-9e2b-7d1c8a4e5f60", "application/json");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).startsWith("{").contains("\"status\":403");
    }
}
