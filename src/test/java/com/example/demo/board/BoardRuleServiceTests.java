package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleRequest;
import com.example.demo.task.RuleEngine;
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
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({BoardService.class, BoardRuleService.class, RuleEngine.class, TaskHistoryService.class, TaskNoteService.class})
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
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(column.getId(), "APPEND_CHECKLIST", List.of(items), null, null, null, null));
    }

    @Test
    void movingIntoTheColumnRunsItsRulesAndRecordsThemAsAutomation() {
        checklistRule(doing, "Prepare", "Thank-you mail");
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, " Follow up ", 14, null, null));
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
                new RuleRequest(null, null, List.of("Review"), null, null, null, false));
        boardService.moveTask(task.getId(), todo.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), todo.getId(), 5, ALICE);
        assertThat(items(task)).containsExactly("Prepare");
    }

    @Test
    void eachStageReplacesTheChecklist() {
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "REPLACE_CHECKLIST", List.of("Prepare", "Research"), null, null, null, null));
        BoardColumn done = columnRepository.findByBoardOrderByPosition(board).get(2);
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(done.getId(), "REPLACE_CHECKLIST", List.of("Research", "Thank-you mail"), null, null, null, null));
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
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Chase", 7, true, null));
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Keep", 7, null, null));
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
    void reminderUsesTheBrowsersTimeZone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Time-Zone", "Pacific/Kiritimati"); // UTC+14, far from any server
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Now", 0, null, null));
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
        ruleService.addRule(board.getId(), email, new RuleRequest(doing.getId(), "APPEND_CHECKLIST", List.of("x"), null, null, null, null));
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
                new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, "Text", -1, null, null))).hasMessageContaining("days");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "ADD_NOTE_REMINDER", null, " ", 3, null, null))).hasMessageContaining("empty");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "DELETE_TASK", null, null, null, null, null))).hasMessageContaining("Unknown action");
        Board other = boardService.createBoard(ALICE, "Other");
        int foreignColumn = columnRepository.findByBoardOrderByPosition(other).get(0).getId();
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(foreignColumn, "ADD_NOTE_REMINDER", null, "Text", 1, null, null))).hasMessageContaining("column of this board");
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
}
