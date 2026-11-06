package com.example.demo.board;

import com.example.demo.board.BoardDtos.DueTask;
import com.example.demo.task.RuleEngine;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskNoteService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({BoardService.class, RuleEngine.class, TaskHistoryService.class, TaskNoteService.class})
class DueTasksTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);

    @Autowired
    BoardService boardService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    BoardMemberRepository memberRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    TaskNoteService noteService;

    User alice;
    User bob;
    Board aliceBoard;
    Board bobBoard;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(new User("Alice", ALICE));
        bob = userRepository.save(new User("Bob", BOB));
        aliceBoard = boardService.createDefaultBoard(alice);
        bobBoard = boardService.createDefaultBoard(bob);
    }

    private List<BoardColumn> columns(Board board) {
        return columnRepository.findByBoardOrderByPosition(board);
    }

    private Task addTask(BoardColumn column, String title, LocalDateTime due) {
        Task task = new Task(title, due, null, null);
        task.setBoardColumn(column);
        return taskRepository.save(task);
    }

    private List<String> titles(String email) {
        return boardService.dueTasksOf(email).stream().map(DueTask::title).toList();
    }

    @Test
    void listsTasksWithADueDateSoonestFirstWithoutTheLastColumn() {
        List<BoardColumn> columns = columns(aliceBoard);
        addTask(columns.get(1), "Later", NOW.plusDays(3));
        addTask(columns.get(0), "Overdue", NOW.minusDays(2));
        addTask(columns.get(0), "No date", null);
        addTask(columns.get(columns.size() - 1), "Done", NOW.minusDays(5));

        assertThat(titles(ALICE)).containsExactly("Overdue", "Later");
        DueTask first = boardService.dueTasksOf(ALICE).get(0);
        assertThat(first.boardSlug()).isEqualTo(aliceBoard.getSlug());
        assertThat(first.columnName()).isEqualTo(columns.get(0).getName());
        assertThat(first.due_date()).isEqualTo(NOW.minusDays(2));
    }

    @Test
    void includesJoinedBoardsButNotOthers() {
        addTask(columns(bobBoard).get(0), "Bob's", NOW);
        assertThat(titles(ALICE)).isEmpty();

        memberRepository.save(new BoardMember(bobBoard, alice));
        List<DueTask> due = boardService.dueTasksOf(ALICE);
        assertThat(due).extracting(DueTask::title).containsExactly("Bob's");
        // Both boards have the default slug; Alice's list gives Bob's board its own.
        assertThat(due.get(0).boardSlug()).isEqualTo(bobBoard.getSlug() + "-2");
    }

    @Test
    void theOnlyColumnOfABoardIsNotDone() {
        List<BoardColumn> columns = columns(aliceBoard);
        Task task = addTask(columns.get(0), "Only column", NOW);
        for (BoardColumn column : columns.subList(1, columns.size())) {
            boardService.deleteColumn(column.getId(), ALICE, null, true);
        }
        assertThat(columns(aliceBoard)).hasSize(1);
        assertThat(titles(ALICE)).containsExactly(task.getTitle());
    }

    @Test
    void notesWithAReminderShowToTheirAuthorBetweenTheTasks() {
        memberRepository.save(new BoardMember(aliceBoard, bob));
        List<BoardColumn> columns = columns(aliceBoard);
        Task task = addTask(columns.get(0), "Apply", NOW.plusDays(2));
        Task done = addTask(columns.get(columns.size() - 1), "Sent", null);
        int noteId = noteService.addNote(task.getId(), ALICE, "Call back", NOW.plusDays(1)).id();
        noteService.addNote(task.getId(), ALICE, "No reminder", null);
        noteService.addNote(task.getId(), BOB, "Bob's reminder", NOW);
        noteService.addNote(done.getId(), ALICE, "On a done task", NOW);

        List<DueTask> due = boardService.dueTasksOf(ALICE);
        assertThat(due).extracting(DueTask::noteText).containsExactly("Call back", null);
        assertThat(due.get(0).id()).isEqualTo(task.getId());
        assertThat(due.get(0).title()).isEqualTo("Apply");
        assertThat(due.get(0).due_date()).isEqualTo(NOW.plusDays(1));
        assertThat(boardService.dueTasksOf(BOB)).extracting(DueTask::noteText).containsExactly("Bob's reminder", null);

        // Done: the reminder goes, the note stays.
        noteService.updateNote(noteId, ALICE, "Call back", null);
        assertThat(boardService.dueTasksOf(ALICE)).extracting(DueTask::noteText).containsExactly((String) null);
        assertThat(noteService.notesOf(task.getId(), ALICE)).extracting(n -> n.text()).contains("Call back");
    }
}
