package com.noteu.noteu.error.reporting;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ErrorReportingInterceptorTest {
    private ErrorEventReporter reporter;
    private ErrorReportingInterceptor interceptor;
    private ErrorCaptureResolver captureResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ErrorReportingProperties properties = new ErrorReportingProperties();
        properties.setEnabled(true);
        properties.setService("noteu");
        properties.setEnvironment("test");
        properties.setDeploySha("test-commit");
        reporter = mock(ErrorEventReporter.class);
        interceptor = new ErrorReportingInterceptor(properties, reporter);
        captureResolver = new ErrorCaptureResolver();
        mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .addInterceptors(interceptor)
                .setHandlerExceptionResolvers(captureResolver, new ExistingResponseResolver())
                .build();
    }

    @Test
    void resolvedServerExceptionKeepsExistingStatusBodyAndOriginalException() throws Exception {
        mvc.perform(get("/fixtures/items/123").queryParam("token", "private-query"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/json"))
                .andExpect(content().string("{\"code\":\"ORIGINAL_ERROR\"}"));

        ErrorEvent event = onlyEvent();
        assertThat(event.service()).isEqualTo("noteu");
        assertThat(event.environment()).isEqualTo("test");
        assertThat(event.deploySha()).isEqualTo("test-commit");
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotBlank();
        assertThat(event.request().method()).isEqualTo("GET");
        assertThat(event.request().routeTemplate()).isEqualTo("/fixtures/items/{id}");
        assertThat(event.request().body()).isNull();
        assertThat(event.request().path()).doesNotContain("private-query", "token=");
        assertThat(event.response().statusCode()).isEqualTo(500);
        assertThat(event.error().type()).contains("FixtureFailure");
        assertThat(event.error().stackTrace()).isNotEmpty();
        assertThat(event.error().message()).doesNotContain("secret-password");
        assertThat(event.error().stackTrace()).noneMatch(line -> line.contains("secret-password"));
    }

    @Test
    void resolvedClientErrorKeepsExistingResponseWithoutReporting() throws Exception {
        mvc.perform(get("/fixtures/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("existing validation message"));
        verifyNoInteractions(reporter);
    }

    @Test
    void successfulRequestIsNotReported() throws Exception {
        mvc.perform(get("/fixtures/healthy"))
                .andExpect(status().isOk())
                .andExpect(content().string("healthy"));
        verifyNoInteractions(reporter);
    }

    @Test
    void exceptionRecoveredByExistingHandlerWithSuccessStatusIsNotReported() throws Exception {
        mvc.perform(get("/fixtures/recovered"))
                .andExpect(status().isOk())
                .andExpect(content().string("existing fallback"));
        verifyNoInteractions(reporter);
    }

    @Test
    void explicitServerErrorResponseIsReportedWithoutInventingAnException() throws Exception {
        mvc.perform(get("/fixtures/status-only"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("existing unavailable body"));
        ErrorEvent event = onlyEvent();
        assertThat(event.response().statusCode()).isEqualTo(503);
        assertThat(event.error().stackTrace()).isEmpty();
        assertThat(event.error().type()).doesNotContain("FixtureFailure");
    }

    @Test
    void unhandledExceptionStillPropagatesAndIsReportedAsServerFailure() {
        assertThatThrownBy(() -> mvc.perform(get("/fixtures/unhandled")))
                .hasRootCauseInstanceOf(UnhandledFailure.class)
                .hasRootCauseMessage("original unhandled failure");
        ErrorEvent event = onlyEvent();
        assertThat(event.response().statusCode()).isEqualTo(500);
        assertThat(event.error().type()).contains("UnhandledFailure");
    }

    @Test
    void reportingFailureCannotChangeTheApplicationResponse() throws Exception {
        doThrow(new IllegalStateException("export unavailable")).when(reporter).report(any());
        mvc.perform(get("/fixtures/items/123"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("{\"code\":\"ORIGINAL_ERROR\"}"));
        verify(reporter).report(any());
    }

    @Test
    void captureResolverObservesExceptionAndLeavesResponseUntouched() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(202);
        assertThat(captureResolver.resolveException(request, response, new Object(),
                new FixtureFailure())).isNull();
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentAsByteArray()).isEmpty();
        verifyNoInteractions(reporter);
    }

    @Test
    void errorRedispatchDoesNotCreateAnotherEventOrReplaceOriginalRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixtures/items/123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/fixtures/items/{id}");
        interceptor.preHandle(request, response, new Object());
        FixtureFailure failure = new FixtureFailure();
        captureResolver.resolveException(request, response, new Object(), failure);
        response.setStatus(500);
        interceptor.afterCompletion(request, response, new Object(), failure);

        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/error");
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(onlyEvent().request().routeTemplate()).isEqualTo("/fixtures/items/{id}");
    }

    @Test
    void requestSnapshotOmitsSessionMatrixParametersHeadersAndBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/fixtures;jsessionid=private-session/items/123;token=private-token");
        request.addHeader("Authorization", "Bearer private-auth");
        request.setQueryString("password=private-query");
        request.setContent("private-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/fixtures/items/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        response.setStatus(500);
        interceptor.afterCompletion(request, response, new Object(), new FixtureFailure());

        ErrorEvent event = onlyEvent();
        assertThat(event.request().path()).isEqualTo("/fixtures/items/123");
        assertThat(event.request().body()).isNull();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event))
                .doesNotContain("private-session", "private-token", "private-auth", "private-query",
                        "private-body", "secret-password");
    }

    @Test
    void asyncExceptionIsReportedOnceAfterFinalDispatch() throws Exception {
        MvcResult pending = mvc.perform(get("/fixtures/async"))
                .andExpect(request().asyncStarted()).andReturn();
        verifyNoInteractions(reporter);
        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("{\"code\":\"ORIGINAL_ERROR\"}"));
        assertThat(onlyEvent().request().routeTemplate()).isEqualTo("/fixtures/async");
    }

    private ErrorEvent onlyEvent() {
        ArgumentCaptor<ErrorEvent> event = ArgumentCaptor.forClass(ErrorEvent.class);
        verify(reporter, times(1)).report(event.capture());
        return event.getValue();
    }

    @RestController
    static class FixtureController {
        @GetMapping("/fixtures/items/{id}")
        String fail(@PathVariable String id) { throw new FixtureFailure(); }

        @GetMapping("/fixtures/bad-request")
        String badRequest() { throw new ExpectedClientFailure(); }

        @GetMapping("/fixtures/healthy")
        String healthy() { return "healthy"; }

        @GetMapping("/fixtures/recovered")
        String recovered() { throw new RecoveredFailure(); }

        @GetMapping("/fixtures/status-only")
        ResponseEntity<String> unavailable() {
            return ResponseEntity.status(503).body("existing unavailable body");
        }

        @GetMapping("/fixtures/unhandled")
        String unhandled() { throw new UnhandledFailure("original unhandled failure"); }

        @GetMapping("/fixtures/async")
        Callable<String> async() { return () -> { throw new FixtureFailure(); }; }
    }

    static class FixtureFailure extends RuntimeException {
        FixtureFailure() { super("exception including secret-password"); }
    }
    static class ExpectedClientFailure extends RuntimeException { }
    static class RecoveredFailure extends RuntimeException { }
    static class UnhandledFailure extends RuntimeException {
        UnhandledFailure(String message) { super(message); }
    }

    /** Represents a pre-existing exception handler; the observer must never replace it. */
    static class ExistingResponseResolver implements HandlerExceptionResolver {
        @Override
        public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                             Object handler, Exception exception) {
            try {
                if (exception instanceof FixtureFailure) {
                    response.setStatus(500);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"ORIGINAL_ERROR\"}");
                    return new ModelAndView();
                }
                if (exception instanceof ExpectedClientFailure) {
                    response.setStatus(400);
                    response.getWriter().write("existing validation message");
                    return new ModelAndView();
                }
                if (exception instanceof RecoveredFailure) {
                    response.setStatus(200);
                    response.getWriter().write("existing fallback");
                    return new ModelAndView();
                }
            } catch (IOException exceptionWritingResponse) {
                throw new IllegalStateException(exceptionWritingResponse);
            }
            return null;
        }
    }
}
