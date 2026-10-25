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
import java.util.List;
import java.util.regex.Pattern;

@Component
public class TrustedAdminAssertionFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = TrustedAdminAssertionFilter.class.getName()
            + ".principal";
    private static final String UUID_SEGMENT =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final List<Pattern> PATHS = List.of(
            Pattern.compile("^/api/v1/admin/community-review/posts$"),
            Pattern.compile("^/api/v1/admin/community-review/posts/" + UUID_SEGMENT + "$"),
            Pattern.compile("^/api/v1/admin/community-review/comments$"),
            Pattern.compile("^/api/v1/admin/community-review/comments/" + UUID_SEGMENT + "$")
    );

    private final TrustedAdminAssertionVerifier verifier;

    public TrustedAdminAssertionFilter(ObjectProvider<TrustedAdminAssertionVerifier> verifierProvider) {
        this.verifier = verifierProvider.getIfAvailable();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || PATHS.stream().noneMatch(pattern -> pattern.matcher(path).matches());
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
