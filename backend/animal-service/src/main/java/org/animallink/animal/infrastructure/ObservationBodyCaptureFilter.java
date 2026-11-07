package org.animallink.animal.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/** Retains the bytes consumed by Jackson so the assertion verifies the actual wire body. */
@Component
public class ObservationBodyCaptureFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod())
                && (request.getRequestURI().equals("/internal/v1/observation-finalizations")
                || request.getRequestURI().equals("/internal/v1/observation-finalizations/attempts/register")
                || request.getRequestURI().equals("/internal/v1/observation-finalizations/attempts/retire"))) {
            // Oversized bodies produce a truncated cache and therefore fail the signed digest.
            chain.doFilter(new ContentCachingRequestWrapper(request, 64 * 1024), response);
        } else {
            chain.doFilter(request, response);
        }
    }
}
