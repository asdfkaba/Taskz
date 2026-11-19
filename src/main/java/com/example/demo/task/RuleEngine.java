package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.board.BoardChanged;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardColumnRepository;
import com.example.demo.board.BoardMemberRepository;
import com.example.demo.board.BoardRule;
import com.example.demo.board.BoardRuleRepository;
import com.example.demo.board.RulePayload;
import com.example.demo.notification.NotificationService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the board rules (see BoardRule.Trigger): when a task enters a column (moved there or created
 * in it), when its days in a column are over (runTimed) and when its checklist is completed.
 * A rule that moves a task runs the rules for entering its new column, and those can't move tasks;
 * what rules change never counts as completing a checklist. So rules can't loop.
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
    private final BoardColumnRepository columnRepository;
    private final PendingMoveRepository pendingRepository;

    // How long the rules wait after a move by hand: the undo in the page is offered that long.
    public static final Duration RULE_DELAY = Duration.ofSeconds(60);

    public RuleEngine(BoardRuleRepository ruleRepository, TaskRepository taskRepository,
                      TaskHistoryService historyService, TaskNoteRepository noteRepository,
                      UserRepository userRepository, BoardMemberRepository memberRepository,
                      NotificationService notificationService, ApplicationEventPublisher events,
                      BoardColumnRepository columnRepository, PendingMoveRepository pendingRepository) {
        this.ruleRepository = ruleRepository;
        this.taskRepository = taskRepository;
        this.historyService = historyService;
        this.noteRepository = noteRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.notificationService = notificationService;
        this.events = events;
        this.columnRepository = columnRepository;
        this.pendingRepository = pendingRepository;
    }

    /**
     * Call after the task was put into its new column. Records what the rules changed. Notes the
     * rules add are written as the user who moved or created the task, so the reminder is theirs.
     */
    @Transactional
    public void taskEntered(Task task, String email) {
        entered(task, email, userZone());
    }

    private void entered(Task task, String email, ZoneId zone) {
        var rules = ruleRepository.findByColumnAndTriggerAndEnabledTrueOrderById(
                task.getBoardColumn(), BoardRule.Trigger.ENTERED_COLUMN.name());
        if (rules.isEmpty()) {
            return;
        }
        TaskHistoryService.Snapshot before = TaskHistoryService.Snapshot.of(task);
        for (BoardRule rule : rules) {
            apply(rule, task, email, zone);
        }
        taskRepository.save(task);
        historyService.recordAutomation(before, task);
    }

    /**
     * Call after a task was moved by hand into another column. Its rules (leaving the column it came
     * from, entering the one it is in) wait {@link #RULE_DELAY}, so a mistake can be undone or corrected
     * without leaving traces: moving on within that time restarts the wait, and only the column it
     * ends up in runs its rules; back where it came from, it is as if it never moved. Either way the
     * history keeps one move entry at most. Someone else moving it, or a move that can't wait (API,
     * rules), first runs what was waiting, see {@link #runWaiting}.
     */
    @Transactional
    public void movedByHand(Task task, BoardColumn from, PendingMove.BoardColumnRef was, String email) {
        BoardColumn to = task.getBoardColumn();
        PendingMove pending = pendingRepository.findById(task.getId()).orElse(null);
        if (pending == null) {
            TaskEvent event = historyService.recordMove(task, from, to, email);
            pending = new PendingMove(task, was, userRepository.findByEmail(email).orElse(null), timeZoneHeader(),
                    event == null ? null : event.getId());
        } else if (pending.getFromColumnId().equals(to.getId())) {
            task.setColumnEntered(pending.getFromEntered());
            historyService.forget(pending.getEventId());
            pendingRepository.delete(pending);
            return;
        } else {
            String origin = columnRepository.findById(pending.getFromColumnId()).map(BoardColumn::getName)
                    .orElse(from.getName());
            if (!historyService.retargetMove(pending.getEventId(), origin, to.getName())) {
                TaskEvent event = historyService.recordMove(task, from, to, email);
                pending.setEventId(event == null ? null : event.getId());
            }
        }
        pending.setDueAt(Instant.now().plus(RULE_DELAY));
        pendingRepository.save(pending);
    }

    /**
     * Runs the rules still waiting for the task's last move by hand, unless keepFor (an email, or
     * null) moved it and so may go on correcting. Call before the task moves in any other way.
     */
    @Transactional
    public void runWaiting(Task task, String keepFor) {
        pendingRepository.findById(task.getId())
                .filter(p -> keepFor == null || p.getUser() == null || !p.getUser().getEmail().equals(keepFor))
                .ifPresent(this::settle);
    }

    /**
     * Takes back the waiting move of the task, for undo: the caller puts it back to the returned place.
     * Only the one who moved it can, and only while its rules wait.
     */
    @Transactional
    public PendingMove takeBack(Task task, String email) {
        PendingMove pending = pendingRepository.findById(task.getId())
                .filter(p -> p.getUser() != null && p.getUser().getEmail().equals(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This move can't be undone anymore"));
        historyService.forget(pending.getEventId());
        pendingRepository.delete(pending);
        return pending;
    }

    /** Runs the rules of moves by hand whose wait is over; every few seconds. */
    // shortcut: a move by hand in the same moment as this job can run its rules twice; fine for one
    // user's board, add a @Version to PendingMove if that ever matters.
    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5S")
    @Transactional
    public void runDueMoves() {
        for (PendingMove pending : pendingRepository.findByDueAtLessThanEqualOrderByDueAt(Instant.now())) {
            settle(pending);
            events.publishEvent(new BoardChanged(pending.getTask().getBoardColumn().getBoard().getId()));
        }
    }

    private void settle(PendingMove pending) {
        Task task = pending.getTask();
        pendingRepository.delete(pending);
        BoardColumn current = task.getBoardColumn();
        columnRepository.findById(pending.getFromColumnId())
                .filter(from -> !from.getId().equals(current.getId()))
                .ifPresent(from -> taskLeft(task, from));
        entered(task, pending.getUser() == null ? null : pending.getUser().getEmail(), zoneOf(pending.getTimeZone()));
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
     * Runs the IN_COLUMN_FOR rules for the tasks whose days in the column are over. Hourly, so a
     * task's turn comes up to an hour late.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    @Transactional
    public void runTimed() {
        Instant now = Instant.now();
        for (BoardRule rule : ruleRepository.findByTriggerAndEnabledTrueOrderById(BoardRule.Trigger.IN_COLUMN_FOR.name())) {
            BoardColumn column = rule.getColumn();
            BoardRule.Action action = BoardRule.Action.valueOf(rule.getAction());
            RulePayload payload = RulePayload.parse(rule.getPayload());
            // Archiving and moving take the task out of the column. A notification leaves it there,
            // so it only goes out for tasks whose days ended since the rule was last looked at.
            Instant after = action == BoardRule.Action.NOTIFY && rule.getCheckedAt() != null
                    ? rule.getCheckedAt().minus(payload.days(), ChronoUnit.DAYS) : Instant.EPOCH;
            List<Task> due = taskRepository.findEnteredBetween(column, after, now.minus(payload.days(), ChronoUnit.DAYS));
            rule.setCheckedAt(now);
            for (Task task : due) {
                switch (action) {
                    case ARCHIVE -> {
                        task.setArchivedAt(now);
                        historyService.recordArchived(task, true, null);
                    }
                    case MOVE -> move(task, payload.targetColumnId(), null);
                    default -> apply(rule, task, null, userZone());
                }
            }
            if (!due.isEmpty()) {
                renumber(column);
                events.publishEvent(new BoardChanged(column.getBoard().getId()));
            }
        }
    }

    /**
     * Call when a user ticked a checklist item and the task's checklist is complete now: runs the
     * CHECKLIST_DONE rules of its column. Returns true if a rule moved the task.
     */
    @Transactional
    public boolean checklistDone(Task task, String email) {
        BoardColumn column = task.getBoardColumn();
        boolean moved = false;
        for (BoardRule rule : ruleRepository.findByColumnAndTriggerAndEnabledTrueOrderById(
                column, BoardRule.Trigger.CHECKLIST_DONE.name())) {
            if (!BoardRule.Action.MOVE.name().equals(rule.getAction())) {
                apply(rule, task, email, userZone());
            } else if (!moved) {
                // The first move counts: after it the task is no longer in this column.
                moved = move(task, RulePayload.parse(rule.getPayload()).targetColumnId(), email);
            }
        }
        if (moved) {
            renumber(column);
        }
        return moved;
    }

    /**
     * Puts the task at the end of the target column, as a rule: the history shows no user. The rules
     * for leaving and entering run, as whoever caused the move (nobody for the hourly job, so a note
     * with a reminder added then reminds no one). The caller renumbers the column the task left.
     */
    private boolean move(Task task, Integer targetColumnId, String email) {
        BoardColumn source = task.getBoardColumn();
        BoardColumn target = targetColumnId == null ? null : columnRepository.findById(targetColumnId).orElse(null);
        if (target == null) {
            return false;
        }
        int last = taskRepository.countByBoardColumn(target);
        runWaiting(task, null);
        taskLeft(task, source);
        task.setBoardColumn(target);
        task.setPosition(last);
        historyService.recordMove(task, source, target, null);
        taskEntered(task, email);
        return true;
    }

    private void renumber(BoardColumn column) {
        List<Task> tasks = taskRepository.findByBoardColumnOrderByPosition(column);
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).setPosition(i);
        }
    }

    // The actions that change or tell about the task where it is; archiving and moving are done by the callers.
    private void apply(BoardRule rule, Task task, String email, ZoneId zone) {
        RulePayload payload = RulePayload.parse(rule.getPayload());
        switch (BoardRule.Action.valueOf(rule.getAction())) {
            case APPEND_CHECKLIST -> Checklists.append(task, payload.items());
            case REPLACE_CHECKLIST -> Checklists.replace(task, payload.items());
            case ADD_NOTE_REMINDER -> {
                TaskNote note = new TaskNote(task, userRepository.findByEmail(email).orElse(null), payload.text());
                note.setRemindAt(LocalDateTime.now(zone).plusDays(payload.days()).truncatedTo(ChronoUnit.MINUTES));
                note.setRuleId(rule.getId());
                noteRepository.save(note);
            }
            case NOTIFY -> notificationService.notify(recipients(task.getBoardColumn().getBoard(), payload.userIds(), email),
                    payload.text(), null, task);
            case ARCHIVE, MOVE -> {
            }
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
        return zoneOf(timeZoneHeader());
    }

    private static String timeZoneHeader() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getHeader(TIME_ZONE_HEADER) : null;
    }

    private static ZoneId zoneOf(String zone) {
        if (zone != null) {
            try {
                return ZoneId.of(zone);
            } catch (DateTimeException e) {
                // Unknown zone: fall back to the server's.
            }
        }
        return ZoneId.systemDefault();
    }
}
