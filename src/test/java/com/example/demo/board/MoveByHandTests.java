package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleRequest;
import com.example.demo.notification.NotificationService;
import com.example.demo.task.PendingMoveRepository;
import com.example.demo.task.RuleEngine;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskHistoryService.EventDto;
import com.example.demo.task.TaskNoteRepository;
import com.example.demo.task.TaskNoteService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Moves from the page: their rules wait a minute, so they can be undone or corrected without traces. */
@DataJpaTest
@Import({BoardService.class, BoardRuleService.class, RuleEngine.class, NotificationService.class, TaskHistoryService.class, TaskNoteService.class})
class MoveByHandTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired BoardService boardService;
    @Autowired BoardRuleService ruleService;
    @Autowired RuleEngine ruleEngine;
    @Autowired TaskHistoryService historyService;
    @Autowired UserRepository userRepository;
    @Autowired BoardColumnRepository columnRepository;
    @Autowired BoardMemberRepository memberRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskNoteRepository noteRepository;
    @Autowired PendingMoveRepository pendingRepository;
    @Autowired EntityManager em;

    Board board;
    BoardColumn sent;      // "Abgeschickt"
    BoardColumn interview;
    BoardColumn rejected;  // "Absage"
    Task task;
    Instant enteredSent;

    @BeforeEach
    void setUp() {
        board = boardService.createDefaultBoard(userRepository.save(new User("Alice", ALICE)));
        List<BoardColumn> columns = columnRepository.findByBoardOrderByPosition(board);
        sent = columns.get(0);
        interview = columns.get(1);
        rejected = columns.get(2);
        memberRepository.save(new BoardMember(board, userRepository.save(new User("Bob", BOB))));

        // Sent: a reminder that goes away when the task leaves. Interview and Rejected: a checklist.
        rule(sent, "ADD_NOTE_REMINDER", null, "Follow up", 7, true);
        rule(interview, "APPEND_CHECKLIST", List.of("Prepare"), null, null, null);
        rule(rejected, "APPEND_CHECKLIST", List.of("Thank-you mail"), null, null, null);

        task = new Task();
        task.setTitle("Acme");
        task.setBoardColumn(sent);
        task = taskRepository.save(task);
        ruleEngine.taskEntered(task, ALICE); // as if created there: the reminder note
        enteredSent = Instant.now().minus(10, ChronoUnit.DAYS);
        task.setColumnEntered(enteredSent);
    }

    private void rule(BoardColumn column, String action, List<String> items, String text, Integer days, Boolean removeOnLeave) {
        ruleService.addRule(board.getId(), ALICE, new RuleRequest(column.getId(), action, items, text, days, removeOnLeave, null, null, null, null));
    }

    private List<String> checklist() {
        return task.getChecklist().stream().map(i -> i.getText()).toList();
    }

    private List<Object> moves() {
        return historyService.historyOf(task).stream().filter(e -> e.type().equals("moved"))
                .map(EventDto::changes).map(c -> c.get("column")).toList();
    }

    // As if the minute were over, then the job's turn.
    private void minutePasses() {
        pendingRepository.findAll().forEach(p -> p.setDueAt(Instant.now().minusSeconds(1)));
        ruleEngine.runDueMoves();
    }

    @Test
    void theRulesWaitAMinuteThenRun() {
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);

        assertThat(checklist()).isEmpty();
        assertThat(noteRepository.findByTaskOrderByCreatedDescIdDesc(task)).hasSize(1);
        assertThat(moves()).containsExactly(List.of("To do", "In progress"));
        assertThat(task.getColumnEntered()).isAfter(enteredSent);

        minutePasses();

        assertThat(checklist()).containsExactly("Prepare");
        assertThat(noteRepository.findByTaskOrderByCreatedDescIdDesc(task)).isEmpty();
        assertThat(pendingRepository.count()).isZero();
    }

    @Test
    void movingOnWithinTheMinuteSkipsTheColumnInBetween() {
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);
        boardService.moveTaskByHand(task.getId(), rejected.getId(), 0, ALICE);
        minutePasses();

        assertThat(moves()).containsExactly(List.of("To do", "Done"));
        assertThat(checklist()).containsExactly("Thank-you mail");
        assertThat(task.getBoardColumn().getId()).isEqualTo(rejected.getId());
    }

    @Test
    void backWhereItCameFromIsAsIfItNeverMoved() {
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);
        boardService.moveTaskByHand(task.getId(), sent.getId(), 0, ALICE);
        minutePasses();

        assertThat(moves()).isEmpty();
        assertThat(checklist()).isEmpty();
        assertThat(noteRepository.findByTaskOrderByCreatedDescIdDesc(task)).hasSize(1);
        assertThat(task.getColumnEntered()).isEqualTo(enteredSent);
    }

    @Test
    void undoPutsItBackToItsPlaceWithoutRules() {
        Task other = new Task();
        other.setTitle("Beta");
        other.setBoardColumn(sent);
        other.setPosition(1);
        taskRepository.save(other);
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);
        boardService.moveTaskByHand(task.getId(), rejected.getId(), 0, ALICE);

        boardService.undoMove(task.getId(), ALICE);
        minutePasses();

        assertThat(task.getBoardColumn().getId()).isEqualTo(sent.getId());
        assertThat(task.getPosition()).isZero();
        assertThat(other.getPosition()).isEqualTo(1);
        assertThat(task.getColumnEntered()).isEqualTo(enteredSent);
        assertThat(moves()).isEmpty();
        assertThat(checklist()).isEmpty();
        assertThat(noteRepository.findByTaskOrderByCreatedDescIdDesc(task)).hasSize(1);
    }

    @Test
    void onlyTheMoverCanUndoAndOnlyWhileTheRulesWait() {
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);
        assertThatThrownBy(() -> boardService.undoMove(task.getId(), BOB))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        minutePasses();
        assertThatThrownBy(() -> boardService.undoMove(task.getId(), ALICE))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void someoneElseMovingItOnFirstRunsWhatWaited() {
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);
        boardService.moveTaskByHand(task.getId(), rejected.getId(), 0, BOB);

        // Alice's move counted: its rules ran, and both moves are in the history.
        assertThat(checklist()).containsExactly("Prepare");
        assertThat(moves()).containsExactly(List.of("In progress", "Done"), List.of("To do", "In progress"));
        minutePasses();
        assertThat(checklist()).containsExactly("Prepare", "Thank-you mail");
    }

    @Test
    void aMoveThatCantWaitFirstRunsWhatWaited() {
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);
        boardService.moveTask(task.getId(), rejected.getId(), 0, ALICE); // e.g. through an API token

        assertThat(checklist()).containsExactly("Prepare", "Thank-you mail");
        assertThat(pendingRepository.count()).isZero();
    }

    @Test
    void deletingTheTaskDropsWhatWaited() {
        boardService.moveTaskByHand(task.getId(), interview.getId(), 0, ALICE);
        em.flush();
        taskRepository.delete(task);
        em.flush();
        em.clear();

        assertThat(pendingRepository.count()).isZero();
        ruleEngine.runDueMoves();
    }
}
