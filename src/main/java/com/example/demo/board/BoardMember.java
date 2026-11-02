package com.example.demo.board;

import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** Someone who joined a board through an invite link (table board_member, see V9__board_members.sql). */
@Entity
@Table(name = "board_member", uniqueConstraints = @UniqueConstraint(name = "board_member_unique", columnNames = {"board_id", "user_id"}))
@Getter
@NoArgsConstructor
public class BoardMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false)
    private Instant joined;

    BoardMember(Board board, User user) {
        this.board = board;
        this.user = user;
        this.joined = Instant.now();
    }
}
