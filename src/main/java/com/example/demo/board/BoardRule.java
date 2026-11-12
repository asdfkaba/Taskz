package com.example.demo.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

/**
 * An automation of a board: when {@link #trigger} happens to a task in {@link #column},
 * {@link #action} runs with the settings in {@link #payload}. Applied by RuleEngine, managed by the owner through BoardRuleService.
 */
@Entity
@Table(name = "board_rule", indexes = @Index(name = "board_rule_column_id", columnList = "column_id"))
@Getter
@Setter
@NoArgsConstructor
public class BoardRule {

    public enum Action { APPEND_CHECKLIST, REPLACE_CHECKLIST, ADD_NOTE_REMINDER, NOTIFY, ARCHIVE, MOVE }

    /** When a rule runs, and what it can do then. */
    public enum Trigger {
        // The task was created in the column or moved into it.
        ENTERED_COLUMN(Action.APPEND_CHECKLIST, Action.REPLACE_CHECKLIST, Action.ADD_NOTE_REMINDER, Action.NOTIFY),
        // The task has been in the column for the payload's days; found by an hourly job.
        IN_COLUMN_FOR(Action.ARCHIVE, Action.NOTIFY, Action.MOVE),
        // Someone ticked the last open checklist item of a task in the column.
        CHECKLIST_DONE(Action.MOVE, Action.NOTIFY);

        private final Set<Action> actions;

        Trigger(Action... actions) {
            this.actions = Set.of(actions);
        }

        public boolean allows(Action action) {
            return actions.contains(action);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "column_id")
    private BoardColumn column;

    // Plain text like TaskEvent.type, not @Enumerated, so new values need no schema change.
    @Column(nullable = false, length = 32)
    private String trigger;

    @Column(nullable = false, length = 32)
    private String action;

    // JSON, see RulePayload: e.g. {"items":["Prepare"]} for APPEND_CHECKLIST, {"days":30} for IN_COLUMN_FOR with ARCHIVE.
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private boolean enabled;

    // When the hourly job last looked at the rule; set anew when the rule is added or changed.
    // An IN_COLUMN_FOR rule that notifies only tells about tasks whose days ended since then.
    @Column(name = "checked_at")
    private Instant checkedAt;

    public BoardRule(BoardColumn column, Trigger trigger, Action action, String payload) {
        this.column = column;
        this.trigger = trigger.name();
        this.checkedAt = Instant.now();
        this.action = action.name();
        this.payload = payload;
        this.enabled = true;
    }
}
