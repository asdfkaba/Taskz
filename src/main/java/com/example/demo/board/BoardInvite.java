package com.example.demo.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** An open invite link of a board (table board_invite, see V9__board_members.sql). */
@Entity
@Table(name = "board_invite")
@Getter
@NoArgsConstructor
public class BoardInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Board board;

    // SHA-256 (hex) of the key in the link; the key itself is only shown once, to the owner.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant created;

    @Column(nullable = false)
    private Instant expires;

    BoardInvite(Board board, String tokenHash, Instant created, Instant expires) {
        this.board = board;
        this.tokenHash = tokenHash;
        this.created = created;
        this.expires = expires;
    }
}
