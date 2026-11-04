package com.example.demo.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An automation of a board: when a task enters {@link #column}, {@link #action} runs with the
 * settings in {@link #payload}. Applied by RuleEngine, managed by the owner through BoardRuleService.
 */
@Entity
@Table(name = "board_rule", indexes = @Index(name = "board_rule_column_id", columnList = "column_id"))
@Getter
@Setter
@NoArgsConstructor
public class BoardRule {

    public enum Trigger { ENTERED_COLUMN }

    public enum Action { APPEND_CHECKLIST, REPLACE_CHECKLIST, SET_DUE_IN }

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

    // JSON: {"items":["Prepare"]} for APPEND_CHECKLIST and REPLACE_CHECKLIST, {"days":14} for SET_DUE_IN.
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private boolean enabled;

    public BoardRule(BoardColumn column, Action action, String payload) {
        this.column = column;
        this.trigger = Trigger.ENTERED_COLUMN.name();
        this.action = action.name();
        this.payload = payload;
        this.enabled = true;
    }
}
