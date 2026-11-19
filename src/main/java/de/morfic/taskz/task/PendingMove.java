package de.morfic.taskz.task;

import de.morfic.taskz.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** A task moved by hand whose rules wait until dueAt (see RuleEngine.movedByHand and V21). */
@Entity
@Table(name = "pending_move", indexes = @Index(name = "pending_move_due_at", columnList = "due_at"))
@Getter
@Setter
@NoArgsConstructor
public class PendingMove {

    @Id
    private Integer taskId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "task_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Task task;

    // Where the task was before the first of the moves, to put it back on undo.
    @Column(nullable = false)
    private Integer fromColumnId;
    @Column(nullable = false)
    private Integer fromPosition;
    @Column(nullable = false)
    private Instant fromEntered;

    // Who moved it: the rules run as them, and only they can undo. null if they are gone.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User user;

    // The mover's browser time zone, for reminders the rules set ("in 14 days").
    @Column(length = 64)
    private String timeZone;

    // The move's history entry.
    private Integer eventId;

    @Column(nullable = false)
    private Instant dueAt;

    PendingMove(Task task, BoardColumnRef from, User user, String timeZone, Integer eventId) {
        this.task = task;
        this.fromColumnId = from.columnId();
        this.fromPosition = from.position();
        this.fromEntered = from.entered();
        this.user = user;
        this.timeZone = timeZone;
        this.eventId = eventId;
    }

    /** Where a task was: column, position in it, since when. */
    public record BoardColumnRef(Integer columnId, int position, Instant entered) {
    }
}
