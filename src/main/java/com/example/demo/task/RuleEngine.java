package com.example.demo.task;

import com.example.demo.board.BoardRule;
import com.example.demo.board.BoardRuleRepository;
import com.example.demo.board.RulePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Runs the board rules of a column when a task enters it (moved there or created in it).
 * Changes made here don't trigger rules again, so rules can't loop.
 */
@Service
public class RuleEngine {

    private static final Logger LOG = LoggerFactory.getLogger(RuleEngine.class);
    // The browser's time zone (authFetch sends it), so "in 14 days" means the user's wall-clock time.
    static final String TIME_ZONE_HEADER = "X-Time-Zone";

    private final BoardRuleRepository ruleRepository;
    private final TaskRepository taskRepository;
    private final TaskHistoryService historyService;

    public RuleEngine(BoardRuleRepository ruleRepository, TaskRepository taskRepository,
                      TaskHistoryService historyService) {
        this.ruleRepository = ruleRepository;
        this.taskRepository = taskRepository;
        this.historyService = historyService;
    }

    /** Call after the task was put into its new column. Records what the rules changed. */
    @Transactional
    public void taskEntered(Task task) {
        var rules = ruleRepository.findByColumnAndTriggerAndEnabledTrueOrderById(
                task.getBoardColumn(), BoardRule.Trigger.ENTERED_COLUMN.name());
        if (rules.isEmpty()) {
            return;
        }
        TaskHistoryService.Snapshot before = TaskHistoryService.Snapshot.of(task);
        for (BoardRule rule : rules) {
            apply(rule, task);
        }
        taskRepository.save(task);
        historyService.recordAutomation(before, task);
    }

    private void apply(BoardRule rule, Task task) {
        RulePayload payload = RulePayload.parse(rule.getPayload());
        switch (BoardRule.Action.valueOf(rule.getAction())) {
            case APPEND_CHECKLIST -> {
                String description = Checklists.append(task.getDescription(), payload.items());
                if (description != null && description.length() > TaskController.MAX_DESCRIPTION_LENGTH) {
                    LOG.warn("Rule {} skipped: description of task {} would get too long", rule.getId(), task.getId());
                    return;
                }
                task.setDescription(description);
            }
            case SET_DUE_IN -> task.setDue_date(
                    LocalDateTime.now(userZone()).plusDays(payload.days()).truncatedTo(ChronoUnit.MINUTES));
        }
    }

    private static ZoneId userZone() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String zone = attributes.getRequest().getHeader(TIME_ZONE_HEADER);
            if (zone != null) {
                try {
                    return ZoneId.of(zone);
                } catch (DateTimeException e) {
                    // Unknown zone: fall back to the server's.
                }
            }
        }
        return ZoneId.systemDefault();
    }
}
