package de.morfic.taskz.board;

import de.morfic.taskz.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
// The slug is unique per user; null only for boards from before slugs existed, until startup fills it in.
@Table(name = "board", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "slug"}))
@Getter
@Setter
@NoArgsConstructor
public class Board {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    // Readable URL part made from the name, e.g. "mein-board". Stays the same once created.
    @Column(length = 60)
    private String slug;

    // Markdown that new tasks of this board start with as their description; null means none.
    @Column(columnDefinition = "text")
    private String taskTemplate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    public Board(String name, User user) {
        this.name = name;
        this.user = user;
    }
}
