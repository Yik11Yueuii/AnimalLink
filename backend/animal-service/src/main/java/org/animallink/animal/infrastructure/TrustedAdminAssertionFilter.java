package org.animallink.animal.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
@Component
public class TrustedAdminAssertionFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = TrustedAdminAssertionFilter.class.getName()
            + ".principal";
    private final TrustedAdminAssertionVerifier verifier;

    public TrustedAdminAssertionFilter(ObjectProvider<TrustedAdminAssertionVerifier> verifierProvider) {
        this.verifier = verifierProvider.getIfAvailable();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !TrustedAdminRequestPolicy.isProtectedPath(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (verifier == null) {
                throw new TrustedAdminAssertionVerifier.AssertionUnavailableException();
            }
            var principal = verifier.verify(request.getHeader(HttpHeaders.AUTHORIZATION),
                    request.getMethod(), request.getRequestURI());
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            filterChain.doFilter(request, response);
        } catch (TrustedAdminAssertionVerifier.AssertionUnavailableException unavailable) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "DOWNSTREAM_IDENTITY_UNAVAILABLE");
        } catch (TrustedAdminAssertionVerifier.AssertionForbiddenException forbidden) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_PERMISSION_REQUIRED");
        } catch (TrustedAdminAssertionVerifier.AssertionRejectedException rejected) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "TRUSTED_IDENTITY_REQUIRED");
        }
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
