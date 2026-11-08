package com.example.demo.board;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A request made with an API token: it acts as the token's user, but only on one board
 * ({@link BoardService#canAccess}) and only through the endpoints the token filter lets through.
 */
public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    private final String email;
    private final int boardId;
    private final Set<ApiScope> scopes;
    private final Instant expiresAt;

    public ApiTokenAuthentication(String email, int boardId, Set<ApiScope> scopes, Instant expiresAt) {
        super(List.of(new SimpleGrantedAuthority("ROLE_API_TOKEN")));
        this.email = email;
        this.boardId = boardId;
        this.scopes = Set.copyOf(scopes);
        this.expiresAt = expiresAt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return email;
    }

    public int boardId() {
        return boardId;
    }

    public Set<ApiScope> scopes() {
        return scopes;
    }

    /** Null if the token never expires. */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** False only while the current request runs with a token for another board. */
    static boolean currentRequestMayUse(Board board) {
        return !(SecurityContextHolder.getContext().getAuthentication() instanceof ApiTokenAuthentication token)
                || token.boardId == board.getId();
    }
}
