package com.example.demo.config;

import com.example.demo.board.ApiScope;
import com.example.demo.board.ApiTokenAuthentication;
import com.example.demo.board.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Logs a request in with an API token ("Authorization: Bearer taskz_..."). Tokens only reach the
 * endpoints listed here, each needing one scope of the token. Any token may ask what it may do and
 * read the upload limits. Everything else (deleting, columns, managing labels, members, sharing,
 * tokens) needs a real login. Which board a token may touch is checked in BoardService.canAccess.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    // scope null: any token.
    private record Endpoint(String method, Pattern path, ApiScope scope) {
        Endpoint(String method, String path, ApiScope scope) {
            this(method, Pattern.compile(path), scope);
        }

        boolean matches(String method, String path) {
            return this.method.equals(method) && this.path.matcher(path).matches();
        }
    }

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("GET", "/token", null),
            new Endpoint("GET", "/attachments/settings", null),
            new Endpoint("GET", "/boards/\\d+", ApiScope.TASKS_READ),
            new Endpoint("GET", "/tasks/\\d+", ApiScope.TASKS_READ),
            new Endpoint("POST", "/tasks", ApiScope.TASKS_CREATE),
            new Endpoint("PUT", "/tasks/\\d+", ApiScope.TASKS_UPDATE),
            new Endpoint("PATCH", "/tasks/\\d+/move", ApiScope.TASKS_MOVE),
            new Endpoint("PATCH", "/tasks/\\d+/checklist/\\d+", ApiScope.CHECKLIST_CHECK),
            new Endpoint("GET", "/tasks/\\d+/attachments", ApiScope.ATTACHMENTS_READ),
            new Endpoint("GET", "/attachments/[0-9a-f-]{36}", ApiScope.ATTACHMENTS_READ),
            new Endpoint("POST", "/tasks/\\d+/attachments", ApiScope.ATTACHMENTS_WRITE),
            new Endpoint("GET", "/tasks/\\d+/notes", ApiScope.NOTES_READ),
            new Endpoint("POST", "/tasks/\\d+/notes", ApiScope.NOTES_WRITE),
            new Endpoint("PATCH", "/notes/\\d+", ApiScope.NOTES_WRITE));

    // A provider, so test slices without the token service still start; there, tokens are refused.
    private final ObjectProvider<ApiTokenService> tokenService;

    public ApiTokenFilter(ObjectProvider<ApiTokenService> tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        ApiTokenService service = tokenService.getIfAvailable();
        Optional<ApiTokenAuthentication> token = service == null
                ? Optional.empty()
                : service.authenticate(header.substring("Bearer ".length()).trim());
        if (token.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unknown or revoked API token");
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!allows(token.get(), request.getMethod(), path)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This API token can't do that");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(token.get());
        filterChain.doFilter(request, response);
    }

    static boolean allows(ApiTokenAuthentication token, String method, String path) {
        return ENDPOINTS.stream().anyMatch(e -> e.matches(method, path) && (e.scope() == null || token.scopes().contains(e.scope())));
    }
}
