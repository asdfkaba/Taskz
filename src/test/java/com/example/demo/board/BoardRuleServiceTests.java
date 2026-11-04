package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleRequest;
import com.example.demo.task.RuleEngine;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
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
@Import({BoardService.class, BoardRuleService.class, RuleEngine.class, TaskHistoryService.class})
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

    private void checklistRule(BoardColumn column, String... items) {
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(column.getId(), "APPEND_CHECKLIST", List.of(items), null, null));
    }

    @Test
    void movingIntoTheColumnRunsItsRulesAndRecordsThemAsAutomation() {
        checklistRule(doing, "Prepare", "Thank-you mail");
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "SET_DUE_IN", null, 14, null));
        Task task = addTask("Notes");

        boardService.moveTask(task.getId(), doing.getId(), 0, BOB);

        assertThat(task.getDescription()).isEqualTo("Notes\n\n- [ ] Prepare\n- [ ] Thank-you mail");
        LocalDateTime expected = LocalDateTime.now().plusDays(14);
        assertThat(task.getDue_date()).isBetween(expected.minusMinutes(2), expected);
        var latest = historyService.historyOf(task).get(0);
        assertThat(latest.type()).isEqualTo("updated");
        assertThat(latest.user()).isNull();
        assertThat(latest.changes()).containsEntry("automation", true).containsKeys("description", "due_date");
    }

    @Test
    void enteringAgainAddsNoDuplicatesAndReorderingOrDisabledRulesDoNothing() {
        checklistRule(doing, "Prepare");
        Task task = addTask(null);
        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), todo.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        assertThat(task.getDescription()).isEqualTo("- [ ] Prepare");

        checklistRule(todo, "Review");
        ruleService.updateRule(ruleRepository.findByColumnBoardOrderById(board).get(1).getId(), ALICE,
                new RuleRequest(null, null, List.of("Review"), null, false));
        boardService.moveTask(task.getId(), todo.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), todo.getId(), 5, ALICE);
        assertThat(task.getDescription()).isEqualTo("- [ ] Prepare");
    }

    @Test
    void dueDateUsesTheBrowsersTimeZone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Time-Zone", "Pacific/Kiritimati"); // UTC+14, far from any server
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(doing.getId(), "SET_DUE_IN", null, 0, null));
        Task task = addTask(null);

        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);

        LocalDateTime there = LocalDateTime.now(ZoneId.of("Pacific/Kiritimati")).truncatedTo(ChronoUnit.MINUTES);
        assertThat(task.getDue_date()).isBetween(there.minusMinutes(1), there);
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
        ruleService.addRule(board.getId(), email, new RuleRequest(doing.getId(), "APPEND_CHECKLIST", List.of("x"), null, null));
    }

    @Test
    void invalidRulesAreRejected() {
        assertThatThrownBy(() -> checklistRule(doing, " ")).hasMessageContaining("1 to 20 items");
        assertThatThrownBy(() -> checklistRule(doing, "a\nb")).hasMessageContaining("one line");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "SET_DUE_IN", null, -1, null))).hasMessageContaining("days");
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(doing.getId(), "DELETE_TASK", null, null, null))).hasMessageContaining("Unknown action");
        Board other = boardService.createBoard(ALICE, "Other");
        int foreignColumn = columnRepository.findByBoardOrderByPosition(other).get(0).getId();
        assertThatThrownBy(() -> ruleService.addRule(board.getId(), ALICE,
                new RuleRequest(foreignColumn, "SET_DUE_IN", null, 1, null))).hasMessageContaining("column of this board");
    }

    @Test
    void deletingTheColumnDeletesItsRulesAndMovedTasksEnterTheTarget() {
        checklistRule(doing, "Prepare");
        checklistRule(todo, "Sort");
        Task task = addTask(null);

        boardService.deleteColumn(todo.getId(), ALICE, doing.getId(), false);

        assertThat(task.getDescription()).isEqualTo("- [ ] Prepare");
        assertThat(ruleRepository.findByColumnBoardOrderById(board)).extracting(r -> r.getColumn().getId())
                .containsExactly(doing.getId());
        boardService.deleteBoard(board.getId(), ALICE);
        assertThat(ruleRepository.count()).isZero();
    }
}
