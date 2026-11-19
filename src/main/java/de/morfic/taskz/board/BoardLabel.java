package de.morfic.taskz.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A named, colored tag. Labels belong to a board; tasks of that board can carry several. */
@Entity
@Table(name = "board_label")
@Getter
@Setter
@NoArgsConstructor
public class BoardLabel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 30)
    private String name;

    // Hex color like "#3b82f6".
    @Column(nullable = false, length = 7)
    private String color;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id")
    private Board board;

    public BoardLabel(String name, String color, Board board) {
        this.name = name;
        this.color = color;
        this.board = board;
    }
}
