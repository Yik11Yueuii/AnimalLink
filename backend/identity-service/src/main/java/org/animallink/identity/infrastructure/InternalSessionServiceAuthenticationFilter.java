package org.animallink.identity.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class InternalSessionServiceAuthenticationFilter extends OncePerRequestFilter {
    public static final String PATH = "/internal/v1/auth/sessions/validate";
    public static final String PRINCIPAL_ATTRIBUTE = InternalSessionServiceAuthenticationFilter.class.getName()
            + ".principal";

    private final ServiceAssertionVerifier verifier;

    public InternalSessionServiceAuthenticationFilter(ServiceAssertionVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            var principal = verifier.verify(request.getHeader(HttpHeaders.AUTHORIZATION));
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            filterChain.doFilter(request, response);
        } catch (ServiceAssertionVerifier.ServiceAssertionUnavailableException unavailable) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "SERVICE_AUTHENTICATION_UNAVAILABLE");
        } catch (ServiceAssertionVerifier.ServiceAssertionRejectedException rejected) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "SERVICE_AUTHENTICATION_FAILED");
        }
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
