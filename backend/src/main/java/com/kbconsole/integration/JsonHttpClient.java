package com.kbconsole.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class JsonHttpClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public record HttpResult(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public HttpResult get(String url, Duration timeout) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), timeout);
    }

    public HttpResult head(String url, Duration timeout) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()), timeout);
    }

    public HttpResult putJson(String url, Object body, Duration timeout) {
        return sendJson("PUT", url, body, timeout);
    }

    public HttpResult postJson(String url, Object body, Duration timeout) {
        return sendJson("POST", url, body, timeout);
    }

    public HttpResult postRaw(String url, String body, String contentType, Duration timeout) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)), timeout);
    }

    private HttpResult sendJson(String method, String url, Object body, Duration timeout) {
        try {
            String json = objectMapper.writeValueAsString(body);
            return send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json)), timeout);
        } catch (IOException ex) {
            throw new IntegrationException("请求体序列化失败：" + ex.getMessage(), ex);
        }
    }

    private HttpResult send(HttpRequest.Builder builder, Duration timeout) {
        try {
            HttpRequest request = builder.timeout(timeout)
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException ex) {
            throw new IntegrationException("网络请求失败：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IntegrationException("网络请求被中断", ex);
        }
    }
}
