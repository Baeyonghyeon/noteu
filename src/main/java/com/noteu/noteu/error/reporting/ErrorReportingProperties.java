package com.noteu.noteu.error.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "noteu.error-reporting")
public class ErrorReportingProperties {
    private boolean enabled = false;
    private URI endpoint = URI.create("http://bdd-analyzer:8000/events");
    private String service = "noteu";
    private String environment = "local";
    private String deploySha = "unknown";
    private int queueCapacity = 64;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(5);

    /** Called only when reporting is enabled or a reporter is explicitly created. */
    public void validate() {
        if (endpoint == null || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !endpoint.getPath().endsWith("/events")) {
            throw new IllegalArgumentException("noteu.error-reporting.endpoint must be an HTTP(S) /events URL without credentials or query parameters");
        }
        requireIdentifier(service, "service");
        requireIdentifier(environment, "environment");
        requireIdentifier(deploySha, "deploy-sha");
        if (queueCapacity < 1 || queueCapacity > 10_000) {
            throw new IllegalArgumentException("noteu.error-reporting.queue-capacity must be between 1 and 10000");
        }
        requireTimeout(connectTimeout, "connect-timeout");
        requireTimeout(requestTimeout, "request-timeout");
    }

    private static void requireIdentifier(String value, String property) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("noteu.error-reporting." + property + " must contain 1 to 128 characters");
        }
    }

    private static void requireTimeout(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("noteu.error-reporting." + property + " must be positive and at most 30 seconds");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getDeploySha() { return deploySha; }
    public void setDeploySha(String deploySha) { this.deploySha = deploySha; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
