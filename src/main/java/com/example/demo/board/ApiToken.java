package com.example.demo.board;

import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** A token for scripts, limited to one board (table api_token, see V15__api_tokens.sql). */
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

    @Column(name = "can_read", nullable = false)
    private boolean canRead;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite;

    @Column(nullable = false)
    private Instant created;

    ApiToken(User user, Board board, String name, String tokenHash, boolean canRead, boolean canWrite, Instant created) {
        this.user = user;
        this.board = board;
        this.name = name;
        this.tokenHash = tokenHash;
        this.canRead = canRead;
        this.canWrite = canWrite;
        this.created = created;
    }
}
