package org.animallink.incident.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IncidentMiniappAssertionFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL = IncidentMiniappAssertionFilter.class.getName() + ".principal";
    private final IncidentMiniappAssertionVerifier verifier;
    public IncidentMiniappAssertionFilter(IncidentMiniappAssertionVerifier verifier) { this.verifier = verifier; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !IncidentMiniappRequestPolicy.isProtected(request.getRequestURI()); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            request.setAttribute(PRINCIPAL, verifier.verify(request.getHeader(HttpHeaders.AUTHORIZATION), request.getMethod(), request.getRequestURI()));
            chain.doFilter(request, response);
        } catch (IncidentMiniappAssertionVerifier.Unavailable e) { reject(response, 503, "DOWNSTREAM_IDENTITY_UNAVAILABLE");
        } catch (IncidentMiniappAssertionVerifier.Forbidden e) { reject(response, 403, "MINIAPP_USER_REQUIRED");
        } catch (IncidentMiniappAssertionVerifier.Rejected e) { reject(response, 401, "TRUSTED_IDENTITY_REQUIRED"); }
    }
    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setCharacterEncoding(StandardCharsets.UTF_8.name()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
