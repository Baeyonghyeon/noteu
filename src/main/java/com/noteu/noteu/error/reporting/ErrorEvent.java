package com.noteu.noteu.error.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Immutable snapshot matching the analyzer's POST /events schema. */
public record ErrorEvent(
        @JsonProperty("event_id") String eventId,
        String service,
        String environment,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("deploy_sha") String deploySha,
        Request request,
        Response response,
        Error error) {

    public record Request(String method, String path,
                          @JsonProperty("route_template") String routeTemplate,
                          Object body) {
    }

    public record Response(@JsonProperty("status_code") int statusCode) {
    }

    public record Error(String type, String message,
                        @JsonProperty("stack_trace") List<String> stackTrace) {
        public Error {
            stackTrace = List.copyOf(stackTrace);
        }
    }
}
