package com.noteu.noteu.error.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Observed request facts only. No user/subject IDs, cookies, query parameters or extra DB reads. */
public record ChatRoomBusinessContext(
        @JsonProperty("auth_state") String authState,
        @JsonProperty("subject_state") String subjectState,
        @JsonProperty("enrollment_state") String enrollmentState) {
    public static final String ROUTE = "/subjects/{subject-id}/chats/rooms";
    private static final String ATTRIBUTE = ChatRoomBusinessContext.class.getName();

    public static void initialize(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        request.setAttribute(ATTRIBUTE, new ChatRoomBusinessContext(
                authenticated ? "authenticated" : "anonymous", "unknown", "unknown"));
    }

    /** Non-empty results from the subject-filtered room query prove subject existence only. */
    public static void observeExistingSubject() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            ChatRoomBusinessContext previous = snapshot(request);
            if (previous != null) {
                request.setAttribute(ATTRIBUTE, new ChatRoomBusinessContext(
                        previous.authState(), "exists", previous.enrollmentState()));
            }
        }
    }

    public static ChatRoomBusinessContext snapshot(HttpServletRequest request) {
        Object context = request.getAttribute(ATTRIBUTE);
        return context instanceof ChatRoomBusinessContext value ? value : null;
    }
}
