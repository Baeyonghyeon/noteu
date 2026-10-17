package com.noteu.noteu.error.reporting;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** Observes exceptions before existing Spring resolvers handle them. */
public class ErrorCaptureResolver implements HandlerExceptionResolver {
    public static final String EXCEPTION_ATTRIBUTE = ErrorCaptureResolver.class.getName() + ".exception";

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception exception) {
        if (request.getAttribute(EXCEPTION_ATTRIBUTE) == null) {
            request.setAttribute(EXCEPTION_ATTRIBUTE, exception);
        }
        // null means "continue the resolver chain". No status, body, or view is changed.
        return null;
    }
}
