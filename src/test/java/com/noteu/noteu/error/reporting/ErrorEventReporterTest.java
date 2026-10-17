package com.noteu.noteu.error.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ErrorEventReporterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private ExecutorService serverThreads;
    private ErrorEventReporter reporter;

    @AfterEach
    void closeResources() {
        if (reporter != null) reporter.close();
        if (server != null) server.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
    }

    @Test
    void postsExactAnalyzerSchemaWithoutChangingFieldNames() throws Exception {
        startServer(exchange -> respond(exchange, 201));
        reporter = new ErrorEventReporter(properties());
        reporter.report(event("event-1"));

        ReceivedRequest received = requests.poll(3, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.path()).isEqualTo("/events");
        assertThat(received.contentType()).startsWith("application/json");
        JsonNode actual = json.readTree(received.body());
        JsonNode expected = json.readTree("""
                {
                  "event_id":"event-1",
                  "service":"noteu",
                  "environment":"test",
                  "occurred_at":"2026-10-17T01:02:03Z",
                  "deploy_sha":"test-commit",
                  "request":{"method":"GET","path":"/subjects/123/chats/rooms",
                    "route_template":"/subjects/{subject-id}/chats/rooms","body":null},
                  "response":{"status_code":500},
                  "error":{"type":"java.lang.IllegalStateException","message":"server failure",
                    "stack_trace":["at com.noteu.noteu.Example.read(Example.java:42)"]}
                }
                """);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void slowAnalyzerDoesNotBlockCallerAndFullQueueDropsExcessEvents() throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        startServer(exchange -> {
            if (requestCount.incrementAndGet() == 1) {
                firstArrived.countDown();
                await(releaseFirst);
            }
            respond(exchange, 200);
        });
        ErrorReportingProperties properties = properties();
        properties.setQueueCapacity(1);
        properties.setRequestTimeout(Duration.ofSeconds(10));
        reporter = new ErrorEventReporter(properties);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> reporter.report(event("first")));
        assertThat(firstArrived.await(3, TimeUnit.SECONDS)).isTrue();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                reporter.report(event("queued"));
                reporter.report(event("queue-full"));
            });
            assertThat(requests).hasSize(1);
        } finally {
            releaseFirst.countDown();
        }

        ReceivedRequest first = requests.poll(3, TimeUnit.SECONDS);
        ReceivedRequest second = requests.poll(3, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(json.readTree(first.body()).get("event_id").asText()).isEqualTo("first");
        assertThat(json.readTree(second.body()).get("event_id").asText()).isEqualTo("queued");
        assertThat(requests.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void timedOutDeliveryDoesNotPreventNextQueuedEvent() throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        startServer(exchange -> {
            if (requestCount.incrementAndGet() == 1) {
                firstArrived.countDown();
                await(releaseFirst);
            }
            respond(exchange, 201);
        });
        ErrorReportingProperties properties = properties();
        properties.setRequestTimeout(Duration.ofMillis(250));
        reporter = new ErrorEventReporter(properties);
        reporter.report(event("times-out"));
        assertThat(firstArrived.await(3, TimeUnit.SECONDS)).isTrue();
        reporter.report(event("after-timeout"));
        try {
            ReceivedRequest first = requests.poll(3, TimeUnit.SECONDS);
            ReceivedRequest second = requests.poll(3, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(json.readTree(second.body()).get("event_id").asText()).isEqualTo("after-timeout");
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void stalledAnalyzerResponseBodyDoesNotBlockTheNextEvent() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        startServer(exchange -> {
            if (requestCount.incrementAndGet() == 1) {
                try (exchange) {
                    exchange.sendResponseHeaders(201, 1024);
                    headersSent.countDown();
                    // The exporter only needs the HTTP status, never this response body.
                    await(releaseBody);
                }
                return;
            }
            respond(exchange, 201);
        });
        ErrorReportingProperties properties = properties();
        properties.setRequestTimeout(Duration.ofSeconds(10));
        reporter = new ErrorEventReporter(properties);
        reporter.report(event("stalled-body"));
        assertThat(headersSent.await(3, TimeUnit.SECONDS)).isTrue();
        reporter.report(event("next-event"));
        try {
            assertThat(requests.poll(3, TimeUnit.SECONDS)).isNotNull();
            ReceivedRequest second = requests.poll(3, TimeUnit.SECONDS);
            assertThat(second).isNotNull();
            assertThat(json.readTree(second.body()).get("event_id").asText()).isEqualTo("next-event");
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void analyzerRejectionDoesNotPoisonReporter() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(exchange -> respond(exchange, requestCount.incrementAndGet() == 1 ? 422 : 201));
        reporter = new ErrorEventReporter(properties());
        reporter.report(event("rejected"));
        reporter.report(event("next"));
        assertThat(requests.poll(3, TimeUnit.SECONDS)).isNotNull();
        ReceivedRequest second = requests.poll(3, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        assertThat(json.readTree(second.body()).get("event_id").asText()).isEqualTo("next");
    }

    @Test
    void callsAfterShutdownAreIgnoredWithoutAffectingApplication() throws Exception {
        startServer(exchange -> respond(exchange, 201));
        reporter = new ErrorEventReporter(properties());
        reporter.close();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> reporter.report(event("after-close")));
        assertThat(requests.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    private ErrorReportingProperties properties() {
        ErrorReportingProperties properties = new ErrorReportingProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events"));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private ErrorEvent event(String eventId) {
        return new ErrorEvent(eventId, "noteu", "test", "2026-10-17T01:02:03Z", "test-commit",
                new ErrorEvent.Request("GET", "/subjects/123/chats/rooms",
                        "/subjects/{subject-id}/chats/rooms", null),
                new ErrorEvent.Response(500),
                new ErrorEvent.Error("java.lang.IllegalStateException", "server failure",
                        List.of("at com.noteu.noteu.Example.read(Example.java:42)")));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "error-reporting-test-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverThreads);
        server.createContext("/events", exchange -> {
            requests.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            handler.handle(exchange);
        });
        server.start();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        try (exchange) {
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler { void handle(HttpExchange exchange) throws IOException; }
    private record ReceivedRequest(String method, String path, String contentType, String body) { }
}
