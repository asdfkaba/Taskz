package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One entry in a task's history: created, updated, moved, deleted or a checkbox ticked, and what changed.
 * task_id has no foreign key on purpose, so the history stays when the task is deleted.
 */
@Entity
@Table(name = "task_event", indexes = @Index(name = "task_event_task_id", columnList = "task_id"))
@Getter
@NoArgsConstructor
public class TaskEvent {

    public enum Type { CREATED, UPDATED, MOVED, DELETED, CHECKED, UNCHECKED, ATTACHED, DETACHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id")
    private Board board;

    @Column(name = "task_id", nullable = false)
    private Integer taskId;

    // Who made the change; null if the user is gone.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private Instant at;

    // Stored as plain text, not @Enumerated: Hibernate would add a CHECK constraint with the current
    // values, and ddl-auto=update never changes it, so a new type would fail on existing databases.
    @Column(nullable = false, length = 20)
    private String type;

    // JSON object, e.g. {"title":["Old","New"],"labelsAdded":["Urgent"]}. Names, not ids, so it stays readable.
    @Column(nullable = false, columnDefinition = "text")
    private String changes;

    TaskEvent(Board board, Integer taskId, User user, Type type, String changes) {
        this.board = board;
        this.taskId = taskId;
        this.user = user;
        this.type = type.name();
        this.changes = changes;
        this.at = Instant.now();
    }
}
