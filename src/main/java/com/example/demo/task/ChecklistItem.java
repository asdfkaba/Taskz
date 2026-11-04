package com.example.demo.task;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One item of a task's checklist. Saved and deleted with the task (see Task.checklist). */
@Entity
@Table(name = "task_checklist_item", indexes = @Index(name = "task_checklist_item_task_id", columnList = "task_id"))
@Getter
@Setter
@NoArgsConstructor
public class ChecklistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id")
    private Task task;

    @Column(nullable = false, length = Checklists.MAX_ITEM_LENGTH)
    private String text;

    @Column(nullable = false)
    private boolean checked;

    // Order within the checklist, starting at 0.
    @Column(nullable = false)
    private int position;

    ChecklistItem(Task task, String text, boolean checked) {
        this.task = task;
        this.text = text;
        this.checked = checked;
    }
}
