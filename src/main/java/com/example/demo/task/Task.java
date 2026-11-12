package com.example.demo.task;

import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardLabel;
import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.*;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name="task")
@Getter
@Setter
@NoArgsConstructor
public class Task {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    Integer id;
    String title;
    // Wall-clock time the user picked, without a zone.
    LocalDateTime due_date;
    // Points in time, stored as timestamptz; the browser shows them in its own zone.
    Instant created;
    Instant edited;

    // Since when the task is in its board's archive; null for a task on the board. An archived task
    // keeps its column, so it can come back, but has no place in the column's order.
    @Column(name = "archived_at")
    Instant archivedAt;

    // Markdown, rendered by the frontend. "text" because varchar(255) is too short.
    @Column(columnDefinition = "text")
    String description;

    public Task(String title, LocalDateTime due_date, Instant created, String description) {
        this.title = title;
        this.due_date = due_date;
        this.created = created;
        this.description = description;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="user_id")
    private User user;

    // The column is the task's status on the board.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="board_column_id")
    private BoardColumn boardColumn;

    // Since when the task is in its column; restarts when it comes back from the archive.
    // An ARCHIVE_AFTER rule of the column counts its days from here (RuleEngine.archiveDue).
    @Column(name = "column_entered", nullable = false)
    private Instant columnEntered;

    // Entering another column restarts columnEntered.
    public void setBoardColumn(BoardColumn column) {
        if (boardColumn == null || !Objects.equals(boardColumn.getId(), column.getId())) {
            columnEntered = Instant.now();
        }
        this.boardColumn = column;
    }

    // Order of the task within its column, starting at 0.
    @Column(nullable = false)
    private int position;

    // Labels of the task's board. BatchSize loads them for many tasks at once when a board is shown.
    @ManyToMany
    @JoinTable(name = "task_label",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id"))
    @BatchSize(size = 100)
    private Set<BoardLabel> labels = new HashSet<>();

    // In order; changed through Checklists, TaskController and RuleEngine. BatchSize as for labels.
    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 100)
    private List<ChecklistItem> checklist = new ArrayList<>();

    // Counted by the database whenever the task is loaded; only for showing it.
    @Formula("(select count(*) from task_attachment a where a.task_id = id)")
    @Setter(AccessLevel.NONE)
    private int attachmentCount;

    @PrePersist
    protected void onCreate() {
        this.created = Instant.now();
    }
    @PreUpdate
    protected void onUpdate() {
        this.edited = Instant.now();
    }
}
