package com.example.demo;

import com.example.demo.board.*;
import com.example.demo.task.Task;
import com.example.demo.task.TaskDto;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs against H2 so the join table and foreign keys are real.
@DataJpaTest
@Import({BoardService.class, LabelService.class, TaskHistoryService.class})
class LabelServiceTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    BoardService boardService;
    @Autowired
    LabelService labelService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    BoardLabelRepository labelRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    EntityManager em;

    Board board;
    BoardColumn todo;
    Board bobsBoard;

    @BeforeEach
    void setUp() {
        board = boardService.createDefaultBoard(userRepository.save(new User("Alice", ALICE)));
        todo = columnRepository.findByBoardOrderByPosition(board).get(0);
        bobsBoard = boardService.createDefaultBoard(userRepository.save(new User("Bob", BOB)));
    }

    private Task addTask(String title, Set<BoardLabel> labels) {
        Task task = new Task();
        task.setTitle(title);
        task.setUser(board.getUser());
        task.setBoardColumn(todo);
        task.setLabels(new java.util.HashSet<>(labels));
        return taskRepository.save(task);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    @Test
    void addTrimsNameAndLowercasesColor() {
        BoardLabel label = labelService.addLabel(board.getId(), ALICE, "  Urgent ", "#EF4444");
        assertThat(label.getName()).isEqualTo("Urgent");
        assertThat(label.getColor()).isEqualTo("#ef4444");
        assertThat(boardService.getBoard(board.getId(), ALICE).labels())
                .extracting(BoardDtos.LabelDto::name).containsExactly("Urgent");
    }

    @Test
    void addRejectsMissingNameOrColor() {
        assertThatThrownBy(() -> labelService.addLabel(board.getId(), ALICE, " ", "#ef4444"))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> labelService.addLabel(board.getId(), ALICE, "x".repeat(31), "#ef4444"))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> labelService.addLabel(board.getId(), ALICE, "Urgent", null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> labelService.addLabel(board.getId(), ALICE, "Urgent", "red"))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void cannotAddLabelToOtherUsersBoard() {
        assertThatThrownBy(() -> labelService.addLabel(bobsBoard.getId(), ALICE, "Mine", "#ef4444"))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void updateChangesOnlyGivenFields() {
        BoardLabel label = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        labelService.updateLabel(label.getId(), ALICE, "Very urgent", null);
        flushAndClear();
        BoardLabel stored = labelRepository.findById(label.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Very urgent");
        assertThat(stored.getColor()).isEqualTo("#ef4444");

        labelService.updateLabel(label.getId(), ALICE, null, "#3B82F6");
        flushAndClear();
        assertThat(labelRepository.findById(label.getId()).orElseThrow().getColor()).isEqualTo("#3b82f6");
    }

    @Test
    void cannotChangeOrDeleteLabelOfOtherUser() {
        BoardLabel bobs = labelService.addLabel(bobsBoard.getId(), BOB, "Bob's", "#ef4444");
        assertThatThrownBy(() -> labelService.updateLabel(bobs.getId(), ALICE, "Hijacked", null))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> labelService.deleteLabel(bobs.getId(), ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void deletingALabelTakesItOffTasks() {
        BoardLabel urgent = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        BoardLabel later = labelService.addLabel(board.getId(), ALICE, "Later", "#64748b");
        Task task = addTask("Task", Set.of(urgent, later));
        flushAndClear();

        labelService.deleteLabel(urgent.getId(), ALICE);
        flushAndClear();

        assertThat(labelRepository.findById(urgent.getId())).isEmpty();
        assertThat(TaskDto.from(taskRepository.findById(task.getId()).orElseThrow()).labelIds())
                .containsExactly(later.getId());
    }

    @Test
    void labelsForTaskAcceptsOnlyLabelsOfTheBoard() {
        BoardLabel urgent = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        BoardLabel bobs = labelService.addLabel(bobsBoard.getId(), BOB, "Bob's", "#ef4444");

        assertThat(labelService.labelsForTask(List.of(urgent.getId()), board)).containsExactly(urgent);
        assertThat(labelService.labelsForTask(List.of(), board)).isEmpty();
        assertThatThrownBy(() -> labelService.labelsForTask(List.of(bobs.getId()), board))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> labelService.labelsForTask(List.of(9999), board))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void boardDetailListsTaskLabelIds() {
        BoardLabel urgent = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        BoardLabel later = labelService.addLabel(board.getId(), ALICE, "Later", "#64748b");
        addTask("Both", Set.of(later, urgent));
        addTask("None", Set.of());
        flushAndClear();

        List<TaskDto> tasks = boardService.getBoard(board.getId(), ALICE).tasks();
        assertThat(tasks.get(0).labelIds()).containsExactly(urgent.getId(), later.getId());
        assertThat(tasks.get(1).labelIds()).isEmpty();
    }

    @Test
    void deletingABoardWithLabeledTasksWorks() {
        BoardLabel urgent = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        addTask("Task", Set.of(urgent));
        flushAndClear();

        boardService.deleteBoard(board.getId(), ALICE);
        flushAndClear();

        assertThat(labelRepository.findById(urgent.getId())).isEmpty();
        assertThat(taskRepository.findAll()).isEmpty();
    }

    @Test
    void deletingAColumnWithLabeledTasksWorks() {
        BoardLabel urgent = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        addTask("Task", Set.of(urgent));
        flushAndClear();

        boardService.deleteColumn(todo.getId(), ALICE, null, true);
        flushAndClear();

        assertThat(taskRepository.findAll()).isEmpty();
        assertThat(labelRepository.findById(urgent.getId())).isPresent();
    }
}
