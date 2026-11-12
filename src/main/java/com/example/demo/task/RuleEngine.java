package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.board.BoardChanged;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardMemberRepository;
import com.example.demo.board.BoardRule;
import com.example.demo.board.BoardRuleRepository;
import com.example.demo.board.RulePayload;
import com.example.demo.notification.NotificationService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the board rules of a column when a task enters it (moved there or created in it), and
 * archives tasks whose time in a column is over (archiveDue).
 * Changes made here don't trigger rules again, so rules can't loop.
 */
@Service
public class RuleEngine {

    // The browser's time zone (authFetch sends it), so "in 14 days" means the user's wall-clock time.
    static final String TIME_ZONE_HEADER = "X-Time-Zone";

    private final BoardRuleRepository ruleRepository;
    private final TaskRepository taskRepository;
    private final TaskHistoryService historyService;
    private final TaskNoteRepository noteRepository;
    private final UserRepository userRepository;
    private final BoardMemberRepository memberRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;

    public RuleEngine(BoardRuleRepository ruleRepository, TaskRepository taskRepository,
                      TaskHistoryService historyService, TaskNoteRepository noteRepository,
                      UserRepository userRepository, BoardMemberRepository memberRepository,
                      NotificationService notificationService, ApplicationEventPublisher events) {
        this.ruleRepository = ruleRepository;
        this.taskRepository = taskRepository;
        this.historyService = historyService;
        this.noteRepository = noteRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.notificationService = notificationService;
        this.events = events;
    }

    /**
     * Call after the task was put into its new column. Records what the rules changed. Notes the
     * rules add are written as the user who moved or created the task, so the reminder is theirs.
     */
    @Transactional
    public void taskEntered(Task task, String email) {
        var rules = ruleRepository.findByColumnAndTriggerAndEnabledTrueOrderById(
                task.getBoardColumn(), BoardRule.Trigger.ENTERED_COLUMN.name());
        if (rules.isEmpty()) {
            return;
        }
        TaskHistoryService.Snapshot before = TaskHistoryService.Snapshot.of(task);
        for (BoardRule rule : rules) {
            apply(rule, task, email);
        }
        taskRepository.save(task);
        historyService.recordAutomation(before, task);
    }

    /** Call when the task leaves the column: deletes the notes its rules added and should remove again. */
    @Transactional
    public void taskLeft(Task task, BoardColumn column) {
        List<Integer> ruleIds = ruleRepository.findByColumnAndTriggerAndEnabledTrueOrderById(
                        column, BoardRule.Trigger.ENTERED_COLUMN.name()).stream()
                .filter(rule -> RulePayload.parse(rule.getPayload()).removesOnLeave())
                .map(BoardRule::getId)
                .toList();
        if (!ruleIds.isEmpty()) {
            noteRepository.deleteByTaskAndRuleIdIn(task, ruleIds);
        }
    }

    /**
     * Archives the tasks that have been in a column for as long as its ARCHIVE_AFTER rule says, like
     * BoardService.archiveTask. Hourly, so a task goes up to an hour after its days are over.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    @Transactional
    public void archiveDue() {
        Instant now = Instant.now();
        for (BoardRule rule : ruleRepository.findByActionAndEnabledTrue(BoardRule.Action.ARCHIVE_AFTER.name())) {
            BoardColumn column = rule.getColumn();
            int days = RulePayload.parse(rule.getPayload()).days();
            List<Task> due = taskRepository.findInColumnSince(column, now.minus(days, ChronoUnit.DAYS));
            if (due.isEmpty()) {
                continue;
            }
            for (Task task : due) {
                task.setArchivedAt(now);
                historyService.recordArchived(task, true, null);
            }
            List<Task> left = taskRepository.findByBoardColumnOrderByPosition(column);
            for (int i = 0; i < left.size(); i++) {
                left.get(i).setPosition(i);
            }
            events.publishEvent(new BoardChanged(column.getBoard().getId()));
        }
    }

    private void apply(BoardRule rule, Task task, String email) {
        RulePayload payload = RulePayload.parse(rule.getPayload());
        switch (BoardRule.Action.valueOf(rule.getAction())) {
            case APPEND_CHECKLIST -> Checklists.append(task, payload.items());
            case REPLACE_CHECKLIST -> Checklists.replace(task, payload.items());
            case ADD_NOTE_REMINDER -> {
                TaskNote note = new TaskNote(task, userRepository.findByEmail(email).orElse(null), payload.text());
                note.setRemindAt(LocalDateTime.now(userZone()).plusDays(payload.days()).truncatedTo(ChronoUnit.MINUTES));
                note.setRuleId(rule.getId());
                noteRepository.save(note);
            }
            case ARCHIVE_AFTER -> {
                // Not on entering: archiveDue does it once the days have passed.
            }
            case NOTIFY -> notificationService.notify(recipients(task.getBoardColumn().getBoard(), payload.userIds(), email),
                    payload.text(), null, task);
        }
    }

    // The chosen people who can still open the board, without whoever caused the rule to run.
    private List<User> recipients(Board board, List<Integer> userIds, String email) {
        List<User> people = new ArrayList<>();
        people.add(board.getUser());
        memberRepository.findByBoardOrderByJoinedAscIdAsc(board).forEach(m -> people.add(m.getUser()));
        return people.stream()
                .filter(user -> userIds.contains(user.getId()) && !user.getEmail().equals(email))
                .toList();
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
