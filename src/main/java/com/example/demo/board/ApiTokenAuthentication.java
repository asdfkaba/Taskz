package com.example.demo.board;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * A request made with an API token: it acts as the token's user, but only on one board
 * ({@link BoardService#canAccess}) and only through the endpoints the token filter lets through.
 */
public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    private final String email;
    private final int boardId;
    private final boolean canRead;
    private final boolean canWrite;

    public ApiTokenAuthentication(String email, int boardId, boolean canRead, boolean canWrite) {
        super(List.of(new SimpleGrantedAuthority("ROLE_API_TOKEN")));
        this.email = email;
        this.boardId = boardId;
        this.canRead = canRead;
        this.canWrite = canWrite;
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

    public boolean canRead() {
        return canRead;
    }

    public boolean canWrite() {
        return canWrite;
    }

    /** False only while the current request runs with a token for another board. */
    static boolean currentRequestMayUse(Board board) {
        return !(SecurityContextHolder.getContext().getAuthentication() instanceof ApiTokenAuthentication token)
                || token.boardId == board.getId();
    }
}
