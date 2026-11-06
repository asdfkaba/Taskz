package com.example.demo;

import com.example.demo.board.*;
import com.example.demo.task.*;
import com.example.demo.task.TaskHistoryService.EventDto;
import com.example.demo.task.TaskHistoryService.Snapshot;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Against H2, so ordering, the missing foreign key on task_id and board deletion are real.
@DataJpaTest
@Import({BoardService.class, RuleEngine.class, LabelService.class, TaskHistoryService.class})
class TaskHistoryServiceTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    BoardService boardService;
    @Autowired
    LabelService labelService;
    @Autowired
    TaskHistoryService history;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    TaskEventRepository eventRepository;
    @Autowired
    EntityManager em;

    Board board;
    BoardColumn todo;
    BoardColumn doing;

    @BeforeEach
    void setUp() {
        board = boardService.createDefaultBoard(userRepository.save(new User("Alice", ALICE)));
        List<BoardColumn> columns = columnRepository.findByBoardOrderByPosition(board);
        todo = columns.get(0);
        doing = columns.get(1);
    }

    private Task addTask(String title, BoardColumn column) {
        Task task = new Task();
        task.setTitle(title);
        task.setUser(board.getUser());
        task.setBoardColumn(column);
        task.setPosition(taskRepository.countByBoardColumn(column));
        taskRepository.save(task);
        history.recordCreated(task, ALICE);
        return task;
    }

    private List<EventDto> historyOf(Task task) {
        em.flush();
        em.clear();
        return history.historyOf(taskRepository.findById(task.getId()).orElse(task));
    }

    @Test
    void creationIsRecordedWithUserAndColumn() {
        Task task = addTask("Write tests", todo);
        List<EventDto> events = historyOf(task);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("created");
        assertThat(events.get(0).user()).isEqualTo("Alice");
        assertThat(events.get(0).changes()).isEqualTo(Map.of("title", "Write tests", "column", "To do"));
    }

    @Test
    void checkboxIsRecordedWithItsText() {
        Task task = addTask("Apply", todo);
        history.recordCheckbox(task, "Cover letter", true, ALICE);
        history.recordCheckbox(task, "Cover letter", false, ALICE);
        List<EventDto> events = historyOf(task);
        assertThat(events).extracting(EventDto::type).containsExactly("unchecked", "checked", "created");
        assertThat(events.get(1).changes()).isEqualTo(Map.of("item", "Cover letter"));
    }

    @Test
    void updateStoresOnlyChangedFieldsWithOldAndNewValue() {
        Task task = addTask("Old", todo);
        Snapshot before = Snapshot.of(task);
        task.setTitle("New");
        task.setDescription("Some **text**");
        task.setDue_date(LocalDateTime.of(2030, 5, 1, 12, 0));
        history.recordUpdate(before, task, ALICE);

        Map<String, Object> changes = historyOf(task).get(0).changes();
        assertThat(changes).containsOnlyKeys("title", "description", "due_date");
        assertThat(changes.get("title")).isEqualTo(List.of("Old", "New"));
        assertThat(changes.get("description")).isEqualTo(java.util.Arrays.asList(null, "Some **text**"));
        assertThat(changes.get("due_date")).isEqualTo(java.util.Arrays.asList(null, "2030-05-01T12:00"));
    }

    @Test
    void saveWithoutChangesRecordsNothing() {
        Task task = addTask("Same", todo);
        history.recordUpdate(Snapshot.of(task), task, ALICE);
        assertThat(historyOf(task)).hasSize(1);
    }

    @Test
    void labelsAreRecordedByName() {
        BoardLabel urgent = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        BoardLabel later = labelService.addLabel(board.getId(), ALICE, "Later", "#64748b");
        Task task = addTask("Task", todo);
        task.getLabels().add(later);

        Snapshot before = Snapshot.of(task);
        task.getLabels().remove(later);
        task.getLabels().add(urgent);
        history.recordUpdate(before, task, ALICE);

        Map<String, Object> changes = historyOf(task).get(0).changes();
        assertThat(changes.get("labelsAdded")).isEqualTo(List.of("Urgent"));
        assertThat(changes.get("labelsRemoved")).isEqualTo(List.of("Later"));
    }

    @Test
    void movingToAnotherColumnIsRecordedButReorderingIsNot() {
        Task first = addTask("First", todo);
        addTask("Second", todo);

        boardService.moveTask(first.getId(), todo.getId(), 1, ALICE);
        assertThat(historyOf(first)).extracting(EventDto::type).containsExactly("created");

        boardService.moveTask(first.getId(), doing.getId(), 0, ALICE);
        List<EventDto> events = historyOf(first);
        assertThat(events).extracting(EventDto::type).containsExactly("moved", "created");
        assertThat(events.get(0).changes().get("column")).isEqualTo(List.of("To do", "In progress"));
    }

    @Test
    void deletingAColumnRecordsMovesOrDeletions() {
        Task moved = addTask("Moved", todo);
        boardService.deleteColumn(todo.getId(), ALICE, doing.getId(), false);
        assertThat(historyOf(moved).get(0).changes().get("column")).isEqualTo(List.of("To do", "In progress"));

        Task gone = addTask("Gone", doing);
        boardService.deleteColumn(doing.getId(), ALICE, null, true);
        // The task is deleted, its history is kept.
        assertThat(taskRepository.findById(gone.getId())).isEmpty();
        assertThat(historyOf(gone)).extracting(EventDto::type).containsExactly("deleted", "created");
    }

    @Test
    void deletingALabelIsRecordedOnItsTasks() {
        BoardLabel urgent = labelService.addLabel(board.getId(), ALICE, "Urgent", "#ef4444");
        Task task = addTask("Task", todo);
        task.getLabels().add(urgent);
        em.flush();

        labelService.deleteLabel(urgent.getId(), ALICE);
        assertThat(historyOf(task).get(0).changes()).isEqualTo(Map.of("labelsRemoved", List.of("Urgent")));
    }

    @Test
    void historyIsNewestFirst() {
        Task task = addTask("A", todo);
        for (String title : List.of("B", "C")) {
            Snapshot before = Snapshot.of(task);
            task.setTitle(title);
            history.recordUpdate(before, task, ALICE);
        }
        assertThat(historyOf(task)).extracting(e -> e.changes().get("title"))
                .containsExactly(List.of("B", "C"), List.of("A", "B"), "A");
    }

    @Test
    void deletingTheBoardDeletesItsHistory() {
        addTask("Task", todo);
        em.flush();
        boardService.deleteBoard(board.getId(), ALICE);
        em.flush();
        assertThat(eventRepository.findAll()).isEmpty();
    }
}
