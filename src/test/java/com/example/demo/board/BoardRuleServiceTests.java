package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleRequest;
import com.example.demo.task.RuleEngine;
import com.example.demo.notification.NotificationRepository;
import com.example.demo.notification.NotificationService;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskNote;
import com.example.demo.task.TaskNoteRepository;
import com.example.demo.task.TaskNoteService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({BoardService.class, BoardRuleService.class, RuleEngine.class, NotificationService.class, TaskHistoryService.class, TaskNoteService.class})
class BoardRuleServiceTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    BoardService boardService;
    @Autowired
    BoardRuleService ruleService;
    @Autowired
    RuleEngine ruleEngine;
    @Autowired
    TaskHistoryService historyService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    BoardMemberRepository memberRepository;
    @Autowired
    BoardRuleRepository ruleRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    TaskNoteRepository noteRepository;
    @Autowired
    TaskNoteService noteService;
    @Autowired
    NotificationRepository notificationRepository;

    Board board;
    BoardColumn todo;
    BoardColumn doing;

    @BeforeEach
    void setUp() {
        board = boardService.createDefaultBoard(userRepository.save(new User("Alice", ALICE)));
        List<BoardColumn> columns = columnRepository.findByBoardOrderByPosition(board);
        todo = columns.get(0);
        doing = columns.get(1);
        memberRepository.save(new BoardMember(board, userRepository.save(new User("Bob", BOB))));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Task addTask(String description) {
        Task task = new Task();
        task.setTitle("Acme");
        task.setDescription(description);
        task.setBoardColumn(todo);
        return taskRepository.save(task);
    }

    // Checklist as "text", ticked ones as "x:text".
    private static List<String> items(Task task) {
        return task.getChecklist().stream().map(i -> (i.isChecked() ? "x:" : "") + i.getText()).toList();
    }

    private void checklistRule(BoardColumn column, String... items) {
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(column.getId(), "APPEND_CHECKLIST", List.of(items), null, null, null, null, null, null, null));
    }

    @Test
    void movingIntoTheColumnRunsItsRulesAndRecordsThemAsAutomation() {
        checklistRule(doing, "Prepare", "Thank-you mail");
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, " Follow up ", 14, null, null, null, null, null));
        Task task = addTask("Notes");

        boardService.moveTask(task.getId(), doing.getId(), 0, BOB);

        assertThat(items(task)).containsExactly("Prepare", "Thank-you mail");
        assertThat(task.getDescription()).isEqualTo("Notes");
        assertThat(task.getDue_date()).isNull();
        var latest = historyService.historyOf(task).get(0);
        assertThat(latest.type()).isEqualTo("updated");
        assertThat(latest.user()).isNull();
        assertThat(latest.changes()).containsEntry("automation", true).containsKeys("checklistAdded");

        // The note is Bob's, who moved the task, so the reminder is his.
        TaskNote note = noteRepository.findByTaskOrderByCreatedDescIdDesc(task).get(0);
        assertThat(note.getText()).isEqualTo("Follow up");
        assertThat(note.getUser().getEmail()).isEqualTo(BOB);
        LocalDateTime expected = LocalDateTime.now().plusDays(14);
        assertThat(note.getRemindAt()).isBetween(expected.minusMinutes(2), expected);
    }

    @Test
    void enteringAgainAddsNoDuplicatesAndReorderingOrDisabledRulesDoNothing() {
        checklistRule(doing, "Prepare");
        Task task = addTask(null);
        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), todo.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        assertThat(items(task)).containsExactly("Prepare");

        checklistRule(todo, "Review");
        ruleService.updateRule(ruleRepository.findByColumnBoardOrderById(board).get(1).getId(), ALICE,
                new RuleRequest(null, null, List.of("Review"), null, null, null, null, false, null, null));
        boardService.moveTask(task.getId(), todo.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), todo.getId(), 5, ALICE);
        assertThat(items(task)).containsExactly("Prepare");
    }

    @Test
    void eachStageReplacesTheChecklist() {
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "REPLACE_CHECKLIST", List.of("Prepare", "Research"), null, null, null, null, null, null, null));
        BoardColumn done = columnRepository.findByBoardOrderByPosition(board).get(2);
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(done.getId(), "REPLACE_CHECKLIST", List.of("Research", "Thank-you mail"), null, null, null, null, null, null, null));
        Task task = addTask("Contact: Ann\n- [ ] Apply");

        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        assertThat(items(task)).containsExactly("Prepare", "Research");

        task.getChecklist().get(1).setChecked(true);
        boardService.moveTask(task.getId(), done.getId(), 0, ALICE);
        assertThat(items(task)).containsExactly("x:Research", "Thank-you mail");
        assertThat(task.getDescription()).isEqualTo("Contact: Ann\n- [ ] Apply");
    }

    @Test
    void aNoteRuleCanRemoveItsNoteWhenTheTaskLeaves() {
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Chase", 7, true, null, null, null, null));
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Keep", 7, null, null, null, null, null));
        assertThat(ruleService.rulesOf(board.getId(), ALICE)).extracting(r -> r.removeOnLeave()).containsExactly(true, false);
        Task task = addTask(null);
        int own = noteService.addNote(task.getId(), ALICE, "Written by hand", null).id();

        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        assertThat(noteTexts(task)).containsExactlyInAnyOrder("Chase", "Keep", "Written by hand");
        boardService.moveTask(task.getId(), doing.getId(), 3, ALICE); // reordering is no leaving
        assertThat(noteTexts(task)).hasSize(3);

        boardService.moveTask(task.getId(), todo.getId(), 0, ALICE);
        assertThat(noteTexts(task)).containsExactlyInAnyOrder("Keep", "Written by hand");
        assertThat(noteRepository.findById(own)).isPresent();

        // Deleting the column moves the task out too.
        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        boardService.deleteColumn(doing.getId(), ALICE, todo.getId(), false);
        assertThat(noteTexts(task)).containsExactlyInAnyOrder("Keep", "Keep", "Written by hand");
    }

    private List<String> noteTexts(Task task) {
        return noteRepository.findByTaskOrderByCreatedDescIdDesc(task).stream().map(TaskNote::getText).toList();
    }

    @Test
    void aNotifyRuleTellsTheChosenPeopleButNotWhoMovedTheTask() {
        int aliceId = userRepository.findByEmail(ALICE).orElseThrow().getId();
        int bobId = userRepository.findByEmail(BOB).orElseThrow().getId();
        ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "NOTIFY", null, " Please review ", null, null, List.of(aliceId, bobId, aliceId), null, null, null));
        assertThat(ruleService.rulesOf(board.getId(), ALICE).get(0).userIds()).containsExactly(aliceId, bobId);
        Task task = addTask(null);

        boardService.moveTask(task.getId(), doing.getId(), 0, BOB);

        assertThat(notificationRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getUser().getEmail()).isEqualTo(ALICE);
            assertThat(n.getText()).isEqualTo("Please review");
            assertThat(n.getTask()).isEqualTo(task);
            assertThat(n.getBoard()).isEqualTo(board);
        });

        // Bob left the board: moving it as Alice tells nobody.
        memberRepository.deleteMember(board, bobId);
        boardService.moveTask(task.getId(), todo.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void aNotifyRuleNeedsTextAndPeopleOfTheBoard() {
        int bobId = userRepository.findByEmail(BOB).orElseThrow().getId();
        int stranger = userRepository.save(new User("Eve", "eve@example.com")).getId();
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "NOTIFY", null, "Hi", null, null, List.of(bobId, stranger), null, null, null)))
                .hasMessageContaining("people of this board");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "NOTIFY", null, "Hi", null, null, List.of(), null, null, null)))
                .hasMessageContaining("people of this board");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "NOTIFY", null, " ", null, null, List.of(bobId), null, null, null)))
                .hasMessageContaining("1 to 500 characters");
    }

    @Test
    void reminderUsesTheBrowsersTimeZone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Time-Zone", "Pacific/Kiritimati"); // UTC+14, far from any server
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Now", 0, null, null, null, null, null));
        Task task = addTask(null);

        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);

        LocalDateTime there = LocalDateTime.now(ZoneId.of("Pacific/Kiritimati")).truncatedTo(ChronoUnit.MINUTES);
        assertThat(noteRepository.findByTaskOrderByCreatedDescIdDesc(task).get(0).getRemindAt())
                .isBetween(there.minusMinutes(1), there);
    }

    @Test
    void onlyTheOwnerManagesRules() {
        assertThatThrownBy(() -> ruleService.rulesOf(board.getId(), BOB)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> checklistRuleAs(BOB)).isInstanceOf(ResponseStatusException.class);
        checklistRule(doing, "Prepare");
        int id = ruleService.rulesOf(board.getId(), ALICE).get(0).id();
        assertThatThrownBy(() -> ruleService.deleteRule(id, BOB)).isInstanceOf(ResponseStatusException.class);
    }

    private void checklistRuleAs(String email) {
        ruleService.addRule(board.getId(), email, new RuleRequest(doing.getId(), "APPEND_CHECKLIST", List.of("x"), null, null, null, null, null, null, null));
    }

    @Test
    void itemsTypedAsMarkdownLoseTheirListPrefix() {
        checklistRule(doing, "- [ ] Folder", "* [x] CV", "1. Cover letter", "[ ]Photo", "- [ ] Folder", "-5 degrees");
        assertThat(ruleService.rulesOf(board.getId(), ALICE).get(0).items())
                .containsExactly("Folder", "CV", "Cover letter", "Photo", "-5 degrees");
        assertThatThrownBy(() -> checklistRule(doing, "- [ ] ")).hasMessageContaining("1 to 20 items");
    }

    @Test
    void invalidRulesAreRejected() {
        assertThatThrownBy(() -> checklistRule(doing, " ")).hasMessageContaining("1 to 20 items");
        assertThatThrownBy(() -> checklistRule(doing, "a\nb")).hasMessageContaining("one line");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Text", -1, null, null, null, null, null))).hasMessageContaining("days");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, " ", 3, null, null, null, null, null))).hasMessageContaining("empty");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "DELETE_TASK", null, null, null, null, null, null, null, null))).hasMessageContaining("Unknown action");
        Board other = boardService.createBoard(ALICE, "Other");
        int foreignColumn = columnRepository.findByBoardOrderByPosition(other).get(0).getId();
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(foreignColumn, "ADD_NOTE_REMINDER", null, "Text", 1, null, null, null, null, null))).hasMessageContaining("column of this board");
    }

    @Test
    void deletingTheColumnDeletesItsRulesAndMovedTasksEnterTheTarget() {
        checklistRule(doing, "Prepare");
        checklistRule(todo, "Sort");
        Task task = addTask(null);

        boardService.deleteColumn(todo.getId(), ALICE, doing.getId(), false);

        assertThat(items(task)).containsExactly("Prepare");
        assertThat(ruleRepository.findByColumnBoardOrderById(board)).extracting(r -> r.getColumn().getId())
                .containsExactly(doing.getId());
        boardService.deleteBoard(board.getId(), ALICE);
        assertThat(ruleRepository.count()).isZero();
    }

    // RuleRequest(columnId, action, items, text, days, removeOnLeave, userIds, enabled, trigger, targetColumnId)
    private void timedRule(BoardColumn column, String action, int days, String text, List<Integer> userIds, Integer target) {
        ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(column.getId(), action, null, text, days, null, userIds, null, "IN_COLUMN_FOR", target));
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    @Test
    void aTimedArchiveRuleArchivesTasksOnceTheirDaysInTheColumnAreOver() {
        timedRule(doing, "ARCHIVE", 30, null, null, null);
        Task old = addTask("old");
        Task fresh = addTask("fresh");
        Task elsewhere = addTask("elsewhere");
        boardService.moveTask(old.getId(), doing.getId(), 0, ALICE);
        boardService.moveTask(fresh.getId(), doing.getId(), 1, ALICE);
        old.setColumnEntered(daysAgo(31));
        elsewhere.setColumnEntered(daysAgo(31));

        ruleEngine.runTimed();

        assertThat(old.getArchivedAt()).isNotNull();
        assertThat(fresh.getArchivedAt()).isNull();
        assertThat(fresh.getPosition()).isZero();
        assertThat(elsewhere.getArchivedAt()).isNull();
        var latest = historyService.historyOf(old).get(0);
        assertThat(latest.type()).isEqualTo("archived");
        assertThat(latest.user()).isNull();
        assertThat(latest.changes()).containsEntry("automation", true);

        // Restored, its days start again: the next run leaves it alone.
        boardService.restoreTask(old.getId(), ALICE);
        ruleEngine.runTimed();
        assertThat(old.getArchivedAt()).isNull();
    }

    @Test
    void aTimedRuleNeedsAtLeastOneDayAndDoesNothingWhenOff() {
        assertThatThrownBy(() -> timedRule(doing, "ARCHIVE", 0, null, null, null)).isInstanceOf(ResponseStatusException.class);
        ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(todo.getId(), "ARCHIVE", null, null, 1, null, null, false, "IN_COLUMN_FOR", null));
        Task task = addTask(null);
        task.setColumnEntered(daysAgo(5));

        ruleEngine.runTimed();

        assertThat(task.getArchivedAt()).isNull();
    }

    @Test
    void aTimedNotifyRuleTellsOnceWhenTheDaysEndAndNotAboutTasksAlreadyOverdue() {
        int aliceId = userRepository.findByEmail(ALICE).orElseThrow().getId();
        Task longAgo = addTask("long ago");
        longAgo.setColumnEntered(daysAgo(20));
        timedRule(todo, "NOTIFY", 7, "Still waiting", List.of(aliceId), null);
        BoardRule rule = ruleRepository.findByColumnBoardOrderById(board).get(0);
        Task task = addTask("just over");
        // As if the rule was last looked at an hour ago, and the task's 7 days ended since.
        rule.setCheckedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        task.setColumnEntered(daysAgo(7).minus(10, ChronoUnit.MINUTES));

        ruleEngine.runTimed();
        ruleEngine.runTimed();

        assertThat(notificationRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo("Still waiting");
            assertThat(n.getTask()).isEqualTo(task);
        });
    }

    @Test
    void aTimedMoveRuleMovesTheTaskLastAndRunsTheRulesOfItsNewColumn() {
        checklistRule(doing, "Prepare");
        timedRule(todo, "MOVE", 3, null, null, doing.getId());
        Task waiting = addTask(null);
        boardService.moveTask(waiting.getId(), doing.getId(), 0, ALICE);
        Task task = addTask(null);
        Task stays = addTask(null);
        stays.setPosition(1);
        task.setColumnEntered(daysAgo(4));

        ruleEngine.runTimed();

        assertThat(task.getBoardColumn().getId()).isEqualTo(doing.getId());
        assertThat(task.getPosition()).isEqualTo(1);
        assertThat(stays.getPosition()).isZero();
        assertThat(items(task)).containsExactly("Prepare");
        var moved = historyService.historyOf(task).stream().filter(e -> e.type().equals("moved")).findFirst().orElseThrow();
        assertThat(moved.user()).isNull();
        assertThat(moved.changes()).containsEntry("automation", true);
    }

    @Test
    void completingTheChecklistMovesTheTaskAndTellsPeople() {
        int aliceId = userRepository.findByEmail(ALICE).orElseThrow().getId();
        ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(todo.getId(), "MOVE", null, null, null, null, null, null, "CHECKLIST_DONE", doing.getId()));
        ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(todo.getId(), "NOTIFY", null, "All done", null, null, List.of(aliceId), null, "CHECKLIST_DONE", null));
        Task task = addTask(null);

        // Entering the column doesn't run them.
        assertThat(notificationRepository.count()).isZero();

        assertThat(ruleEngine.checklistDone(task, BOB)).isTrue();

        assertThat(task.getBoardColumn().getId()).isEqualTo(doing.getId());
        assertThat(notificationRepository.findAll()).singleElement()
                .satisfies(n -> assertThat(n.getText()).isEqualTo("All done"));
    }

    @Test
    void triggersOnlyTakeTheirOwnActionsAndAMoveNeedsAnotherColumnOfTheBoard() {
        // Archiving on entering, a checklist on a timer, an unknown trigger.
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(todo.getId(), "ARCHIVE", null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(todo.getId(), "APPEND_CHECKLIST", List.of("A"), null, 3, null, null, null, "IN_COLUMN_FOR", null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(todo.getId(), "MOVE", null, null, null, null, null, null, "SOMETIMES", doing.getId())))
                .isInstanceOf(ResponseStatusException.class);
        // Into its own column, into a column of another board.
        assertThatThrownBy(() -> timedRule(todo, "MOVE", 3, null, null, todo.getId())).isInstanceOf(ResponseStatusException.class);
        Board other = boardService.createBoard(ALICE, "Other");
        int foreign = columnRepository.findByBoardOrderByPosition(other).get(0).getId();
        assertThatThrownBy(() -> timedRule(todo, "MOVE", 3, null, null, foreign)).isInstanceOf(ResponseStatusException.class);
        assertThat(ruleService.rulesOf(board.getId(), ALICE)).isEmpty();
    }

    @Test
    void deletingAColumnDeletesTheRulesThatMoveTasksIntoIt() {
        timedRule(todo, "MOVE", 3, null, null, doing.getId());
        timedRule(todo, "ARCHIVE", 3, null, null, null);

        boardService.deleteColumn(doing.getId(), ALICE, null, false);

        assertThat(ruleService.rulesOf(board.getId(), ALICE)).extracting("action").containsExactly("ARCHIVE");
    }
}
