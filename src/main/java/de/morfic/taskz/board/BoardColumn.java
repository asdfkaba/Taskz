package de.morfic.taskz.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "board_column")
@Getter
@Setter
@NoArgsConstructor
public class BoardColumn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    // Order of the column within its board, starting at 0.
    @Column(nullable = false)
    private int position;

    // Hex color like "#3b82f6", or null for the default look.
    @Column(length = 7)
    private String color;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id")
    private Board board;

    public BoardColumn(String name, String color, int position, Board board) {
        this.name = name;
        this.color = color;
        this.position = position;
        this.board = board;
    }
}
