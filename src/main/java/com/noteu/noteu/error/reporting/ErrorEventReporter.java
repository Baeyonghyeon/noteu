package com.noteu.noteu.error.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Best-effort transport: a failed analyzer must never delay or fail a noteu response. */
public class ErrorEventReporter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ErrorEventReporter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final ThreadPoolExecutor executor;
    private final AtomicLong failureCount = new AtomicLong();

    public ErrorEventReporter(ErrorReportingProperties properties) {
        properties.validate();
        endpoint = properties.getEndpoint();
        requestTimeout = properties.getRequestTimeout();
        client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()), runnable -> {
                    Thread thread = new Thread(runnable, "noteu-error-reporter");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Queue only immutable data, never a servlet request or a Throwable. */
    public void report(ErrorEvent event) {
        try {
            executor.execute(() -> send(event));
        } catch (RejectedExecutionException exception) {
            recordFailure("queue full or reporter stopped", null);
        } catch (RuntimeException exception) {
            recordFailure("cannot enqueue event", null);
        }
    }

    private void send(ErrorEvent event) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(event)))
                    .build();
            // Synchronous I/O is confined to the one background worker, bounding concurrency.
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // The status is enough. Closing immediately also avoids waiting for a slow response body.
            try (InputStream ignored = response.body()) {
                if (response.statusCode() != 200 && response.statusCode() != 201) {
                    recordFailure("analyzer rejected event", response.statusCode());
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure("delivery interrupted", null);
        } catch (Exception exception) {
            // Do not log exception messages: network/JSON errors can include request data.
            recordFailure("delivery failed", null);
        }
    }

    private void recordFailure(String reason, Integer status) {
        long count = failureCount.incrementAndGet();
        // Log the first failure and then a summary every 100 failures during an outage.
        if (count == 1 || count % 100 == 0) {
            log.warn("Error event dropped: {}, HTTP status={}, total delivery failures={}", reason, status, count);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
