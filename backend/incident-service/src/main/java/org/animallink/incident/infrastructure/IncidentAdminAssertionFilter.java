package org.animallink.incident.infrastructure;

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
public class IncidentAdminAssertionFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL = IncidentAdminAssertionFilter.class.getName() + ".principal";
    private final IncidentAdminAssertionVerifier verifier;
    public IncidentAdminAssertionFilter(IncidentAdminAssertionVerifier verifier) { this.verifier = verifier; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !IncidentAdminRequestPolicy.isProtected(request.getRequestURI()); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            request.setAttribute(PRINCIPAL, verifier.verify(request.getHeader(HttpHeaders.AUTHORIZATION), request.getMethod(), request.getRequestURI()));
            chain.doFilter(request, response);
        } catch (IncidentAdminAssertionVerifier.Unavailable e) { reject(response, 503, "DOWNSTREAM_IDENTITY_UNAVAILABLE"); }
        catch (IncidentAdminAssertionVerifier.Forbidden e) { reject(response, 403, "ADMIN_PERMISSION_REQUIRED"); }
        catch (IncidentAdminAssertionVerifier.Rejected e) { reject(response, 401, "TRUSTED_IDENTITY_REQUIRED"); }
    }
    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setCharacterEncoding(StandardCharsets.UTF_8.name()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
