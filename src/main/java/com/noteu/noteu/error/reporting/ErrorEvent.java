package com.noteu.noteu.error.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

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
        Error error,
        @JsonProperty("business_context") @JsonInclude(JsonInclude.Include.NON_NULL)
        ChatRoomBusinessContext businessContext) {

    /** Preserve the existing event schema for endpoints without business context. */
    public ErrorEvent(String eventId, String service, String environment, String occurredAt,
                      String deploySha, Request request, Response response, Error error) {
        this(eventId, service, environment, occurredAt, deploySha, request, response, error, null);
    }

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
