package com.example.demo.config;

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
 * endpoints listed here: what the token may do (any token), reading the board and its tasks, and
 * creating and changing tasks, giving them labels included. Everything else (deleting, columns,
 * managing labels, members, sharing, tokens) needs a real login. Which board a token may touch is
 * checked in BoardService.canAccess.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private record Endpoint(String method, Pattern path) {
        Endpoint(String method, String path) {
            this(method, Pattern.compile(path));
        }

        boolean matches(String method, String path) {
            return this.method.equals(method) && this.path.matcher(path).matches();
        }
    }

    private static final List<Endpoint> ANY = List.of(
            new Endpoint("GET", "/token"));

    private static final List<Endpoint> READ = List.of(
            new Endpoint("GET", "/boards/\\d+"),
            new Endpoint("GET", "/tasks/\\d+"));

    private static final List<Endpoint> WRITE = List.of(
            new Endpoint("POST", "/tasks"),
            new Endpoint("PUT", "/tasks/\\d+"),
            new Endpoint("PATCH", "/tasks/\\d+/move"),
            new Endpoint("PATCH", "/tasks/\\d+/checklist/\\d+"));

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
        return ANY.stream().anyMatch(e -> e.matches(method, path))
                || (token.canRead() && READ.stream().anyMatch(e -> e.matches(method, path)))
                || (token.canWrite() && WRITE.stream().anyMatch(e -> e.matches(method, path)));
    }
}
