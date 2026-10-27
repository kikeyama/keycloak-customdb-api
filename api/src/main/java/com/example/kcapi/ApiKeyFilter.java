package com.example.kcapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** /api/** を共有 API キー (X-API-Key) で保護する。ヘルスチェック等は対象外。 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public ApiKeyFilter(AppProperties props) {
        this.expected = props.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-API-Key");
        if (provided == null) {
            AuditLog.apiKeyRejected(request, AuditLog.Reason.API_KEY_MISSING);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected)) {
            AuditLog.apiKeyRejected(request, AuditLog.Reason.API_KEY_MISMATCH);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
