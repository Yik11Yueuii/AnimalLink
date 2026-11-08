package org.animallink.intelligence.infrastructure;

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
public class IntelligenceMiniappAssertionFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL = IntelligenceMiniappAssertionFilter.class.getName() + ".principal";
    private final IntelligenceMiniappAssertionVerifier verifier;
    public IntelligenceMiniappAssertionFilter(IntelligenceMiniappAssertionVerifier verifier) { this.verifier = verifier; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !IntelligenceMiniappRequestPolicy.isProtected(request.getRequestURI()); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            request.setAttribute(PRINCIPAL, verifier.verify(request.getHeader(HttpHeaders.AUTHORIZATION), request.getMethod(), request.getRequestURI()));
            chain.doFilter(request, response);
        } catch (IntelligenceMiniappAssertionVerifier.Unavailable e) { reject(response, 503, "DOWNSTREAM_IDENTITY_UNAVAILABLE");
        } catch (IntelligenceMiniappAssertionVerifier.Forbidden e) { reject(response, 403, "MINIAPP_USER_REQUIRED");
        } catch (IntelligenceMiniappAssertionVerifier.Rejected e) { reject(response, 401, "TRUSTED_IDENTITY_REQUIRED"); }
    }
    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setCharacterEncoding(StandardCharsets.UTF_8.name()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
