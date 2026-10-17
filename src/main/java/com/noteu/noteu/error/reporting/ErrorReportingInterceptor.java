package com.noteu.noteu.error.reporting;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class ErrorReportingInterceptor implements AsyncHandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ErrorReportingInterceptor.class);
    private static final String CONTEXT_ATTRIBUTE = ErrorReportingInterceptor.class.getName() + ".context";
    private static final int MAX_STACK_ENTRIES = 48;
    private final ErrorReportingProperties properties;
    private final ErrorEventReporter reporter;

    public ErrorReportingInterceptor(ErrorReportingProperties properties, ErrorEventReporter reporter) {
        this.properties = properties;
        this.reporter = reporter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(CONTEXT_ATTRIBUTE) == null && request.getDispatcherType() != DispatcherType.ERROR) {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            // A matched route is required; /error alone cannot identify the original route reliably.
            if (pattern != null && !"/error".equals(pattern.toString())) {
                // Matrix parameters can contain URL-rewritten session IDs; omit them too.
                request.setAttribute(CONTEXT_ATTRIBUTE, new RequestContext(
                        UUID.randomUUID().toString(), Instant.now().toString(),
                        bounded(request.getMethod(), 16), bounded(request.getRequestURI().replaceAll(";[^/]*", ""), 2048),
                        bounded(pattern.toString(), 2048)));
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        try {
            Object value = request.getAttribute(CONTEXT_ATTRIBUTE);
            if (!(value instanceof RequestContext context) || request.isAsyncStarted()) {
                return;
            }
            int status = response.getStatus();
            if (status < 500) {
                // An unhandled exception reaches this callback before the servlet container sets 500.
                if (exception == null || status >= 400 || isCompletedEventStream(response)) {
                    return;
                }
                status = 500;
            }
            if (!context.submitted.compareAndSet(false, true)) {
                return;
            }
            Throwable original = originalException(request, exception);
            ErrorEvent.Error error = original == null
                    ? new ErrorEvent.Error("HttpServerError", "HTTP " + status + " response without a captured exception", List.of())
                    : new ErrorEvent.Error(bounded(original.getClass().getName(), 512),
                    "Server exception captured; exception message omitted to protect request data", stackTrace(original));
            reporter.report(new ErrorEvent(context.eventId, properties.getService(), properties.getEnvironment(),
                    context.occurredAt, properties.getDeploySha(),
                    new ErrorEvent.Request(context.method, context.path, context.routeTemplate, null),
                    new ErrorEvent.Response(status), error));
        } catch (RuntimeException reportingFailure) {
            // Reporting is observational; it must not replace a controller response or exception.
            log.warn("Error reporting failed while capturing a request; existing response preserved");
        }
    }

    private static boolean isCompletedEventStream(HttpServletResponse response) {
        String contentType = response.getContentType();
        return response.isCommitted() && contentType != null && contentType.startsWith("text/event-stream");
    }

    private static Throwable originalException(HttpServletRequest request, Exception exception) {
        Object captured = request.getAttribute(ErrorCaptureResolver.EXCEPTION_ATTRIBUTE);
        if (captured instanceof Throwable throwable) {
            return throwable;
        }
        if (exception != null) {
            return exception;
        }
        Object containerException = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        return containerException instanceof Throwable throwable ? throwable : null;
    }

    private static List<String> stackTrace(Throwable original) {
        List<String> entries = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = original;
        for (int cause = 0; current != null && cause < 4 && entries.size() < MAX_STACK_ENTRIES; cause++) {
            if (!seen.add(current)) {
                break;
            }
            if (cause > 0) {
                entries.add("Caused by: " + bounded(current.getClass().getName(), 512));
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                if (entries.size() == MAX_STACK_ENTRIES) {
                    break;
                }
                entries.add("at " + bounded(frame.toString(), 512));
            }
            current = current.getCause();
        }
        return entries;
    }

    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static final class RequestContext {
        private final String eventId;
        private final String occurredAt;
        private final String method;
        private final String path;
        private final String routeTemplate;
        private final AtomicBoolean submitted = new AtomicBoolean();

        private RequestContext(String eventId, String occurredAt, String method, String path, String routeTemplate) {
            this.eventId = eventId;
            this.occurredAt = occurredAt;
            this.method = method;
            this.path = path;
            this.routeTemplate = routeTemplate;
        }
    }
}
