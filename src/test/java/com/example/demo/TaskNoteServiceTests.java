package com.example.demo;

import com.example.demo.notification.NotificationService;
import com.example.demo.board.*;
import com.example.demo.task.*;
import com.example.demo.task.TaskNoteService.NoteDto;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({TaskNoteService.class, BoardService.class, RuleEngine.class, NotificationService.class, TaskHistoryService.class})
class TaskNoteServiceTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    TaskNoteService notes;
    @Autowired
    BoardService boardService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    TaskNoteRepository noteRepository;
    @Autowired
    EntityManager em;

    Board board;
    BoardColumn todo;
    Task task;

    @BeforeEach
    void setUp() {
        board = boardService.createDefaultBoard(userRepository.save(new User("Alice", ALICE)));
        boardService.createDefaultBoard(userRepository.save(new User("Bob", BOB)));
        todo = columnRepository.findByBoardOrderByPosition(board).get(0);
        task = new Task();
        task.setTitle("Bewerbung");
        task.setUser(board.getUser());
        task.setBoardColumn(todo);
        taskRepository.save(task);
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @Test
    void notesAreListedNewestFirstWithAuthor() {
        notes.addNote(task.getId(), ALICE, "First call", null);
        notes.addNote(task.getId(), ALICE, "Second call", null);
        flushAndClear();

        List<NoteDto> list = notes.notesOf(task.getId(), ALICE);
        assertThat(list).extracting(NoteDto::text).containsExactly("Second call", "First call");
        assertThat(list.get(0).user()).isEqualTo("Alice");
        assertThat(list.get(0).edited()).isNull();
    }

    @Test
    void textIsTrimmedAtTheEndsButKeepsLineBreaks() {
        NoteDto note = notes.addNote(task.getId(), ALICE, "  Line one\nLine two \n ", null);
        assertThat(note.text()).isEqualTo("Line one\nLine two");
    }

    @Test
    void emptyOrTooLongNotesAreRejected() {
        assertThatThrownBy(() -> notes.addNote(task.getId(), ALICE, "  \n ", null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> notes.addNote(task.getId(), ALICE, null, null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> notes.addNote(task.getId(), ALICE, "x".repeat(5_001), null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThat(notes.addNote(task.getId(), ALICE, "x".repeat(5_000), null).text()).hasSize(5_000);
    }

    @Test
    void editingMarksTheNoteAsEditedOnlyWhenTheTextChanged() {
        NoteDto note = notes.addNote(task.getId(), ALICE, "Call at 10", null);
        assertThat(notes.updateNote(note.id(), ALICE, " Call at 10 ", null).edited()).isNull();

        NoteDto changed = notes.updateNote(note.id(), ALICE, "Call at 11", null);
        assertThat(changed.text()).isEqualTo("Call at 11");
        assertThat(changed.edited()).isNotNull();
    }

    @Test
    void deletingANoteRemovesIt() {
        NoteDto note = notes.addNote(task.getId(), ALICE, "Temporary", null);
        notes.deleteNote(note.id(), ALICE);
        flushAndClear();
        assertThat(notes.notesOf(task.getId(), ALICE)).isEmpty();
    }

    @Test
    void otherUsersCannotSeeOrChangeNotes() {
        NoteDto note = notes.addNote(task.getId(), ALICE, "Private", null);
        assertThatThrownBy(() -> notes.notesOf(task.getId(), BOB)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> notes.addNote(task.getId(), BOB, "Hi", null)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> notes.updateNote(note.id(), BOB, "Hijacked", null)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> notes.deleteNote(note.id(), BOB)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void deletingTheTaskDeletesItsNotes() {
        notes.addNote(task.getId(), ALICE, "Goes with the task", null);
        flushAndClear();
        taskRepository.delete(taskRepository.findById(task.getId()).orElseThrow());
        flushAndClear();
        assertThat(noteRepository.findAll()).isEmpty();
    }

    @Test
    void deletingAColumnOrBoardWithNotesWorks() {
        notes.addNote(task.getId(), ALICE, "In the column", null);
        flushAndClear();
        boardService.deleteColumn(todo.getId(), ALICE, null, true);
        flushAndClear();
        assertThat(noteRepository.findAll()).isEmpty();

        Task other = new Task();
        other.setTitle("Other");
        other.setUser(board.getUser());
        other.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        taskRepository.save(other);
        notes.addNote(other.getId(), ALICE, "On the board", null);
        flushAndClear();
        boardService.deleteBoard(board.getId(), ALICE);
        flushAndClear();
        assertThat(noteRepository.findAll()).isEmpty();
    }
}
