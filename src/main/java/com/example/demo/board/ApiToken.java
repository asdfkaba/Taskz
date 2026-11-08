package com.example.demo.board;

import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** A token for scripts, limited to one board (table api_token, see V15__api_tokens.sql and V16). */
@Entity
@Table(name = "api_token")
@Getter
@NoArgsConstructor
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Board board;

    @Column(nullable = false, length = 50)
    private String name;

    // SHA-256 (hex) of the token; the token itself is only shown once, when it is created.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    // ApiScope values, comma-separated, e.g. "tasks:read,notes:write".
    @Column(nullable = false, length = 500)
    private String scopes;

    @Column(nullable = false)
    private Instant created;

    // Null: never expires.
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    ApiToken(User user, Board board, String name, String tokenHash, Set<ApiScope> scopes, Instant created, Instant expiresAt) {
        this.user = user;
        this.board = board;
        this.name = name;
        this.tokenHash = tokenHash;
        this.scopes = scopes.stream().sorted().map(ApiScope::value).collect(Collectors.joining(","));
        this.created = created;
        this.expiresAt = expiresAt;
    }

    public Set<ApiScope> scopeSet() {
        Set<ApiScope> result = EnumSet.noneOf(ApiScope.class);
        Arrays.stream(scopes.split(",")).map(ApiScope::of).forEach(s -> s.ifPresent(result::add));
        return result;
    }

    boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    void used(Instant now) {
        lastUsedAt = now;
    }
}
