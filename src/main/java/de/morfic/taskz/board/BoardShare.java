package de.morfic.taskz.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A read-only link to a board: whoever knows the token can view the board without logging in. */
@Entity
@Table(name = "board_share")
@Getter
@Setter
@NoArgsConstructor
public class BoardShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id")
    private Board board;

    // Random UUID (122 random bits from a secure generator), so links can't be guessed.
    @Column(nullable = false, unique = true)
    private UUID token;

    @Column(nullable = false)
    private Instant created;

    // What the link shows besides the board; all off for a new link.
    @Column(nullable = false)
    private boolean showAttachments;

    @Column(nullable = false)
    private boolean showNotes;

    @Column(nullable = false)
    private boolean showHistory;

    public BoardShare(Board board) {
        this.board = board;
        this.token = UUID.randomUUID();
        this.created = Instant.now();
    }
}
