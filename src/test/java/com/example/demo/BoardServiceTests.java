package com.example.demo;

import com.example.demo.task.RuleEngine;
import com.example.demo.board.*;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.task.TaskController;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

// Runs against H2 so the ordering logic is checked with real queries.
@DataJpaTest
@Import({BoardService.class, RuleEngine.class, TaskHistoryService.class})
@RecordApplicationEvents
class BoardServiceTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    BoardService boardService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    BoardRepository boardRepository;
    @Autowired
    EntityManager em;
    @Autowired
    ApplicationEvents events;

    Board board;
    BoardColumn todo;
    BoardColumn doing;
    BoardColumn done;

    @BeforeEach
    void setUp() {
        User alice = userRepository.save(new User("Alice", ALICE));
        board = boardService.createDefaultBoard(alice);
        List<BoardColumn> columns = columnRepository.findByBoardOrderByPosition(board);
        todo = columns.get(0);
        doing = columns.get(1);
        done = columns.get(2);
    }

    private Task addTask(String title, BoardColumn column) {
        Task task = new Task();
        task.setTitle(title);
        task.setUser(column.getBoard().getUser());
        task.setBoardColumn(column);
        task.setPosition(taskRepository.countByBoardColumn(column));
        return taskRepository.save(task);
    }

    private List<String> titlesIn(BoardColumn column) {
        em.flush();
        em.clear();
        BoardColumn fresh = columnRepository.findById(column.getId()).orElseThrow();
        List<Task> tasks = taskRepository.findByBoardColumnOrderByPosition(fresh);
        for (int i = 0; i < tasks.size(); i++) {
            assertThat(tasks.get(i).getPosition()).as("positions have no gaps").isEqualTo(i);
        }
        return tasks.stream().map(Task::getTitle).toList();
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    @Test
    void defaultBoardHasThreeColumnsInOrder() {
        assertThat(columnRepository.findByBoardOrderByPosition(board))
                .extracting(BoardColumn::getName, BoardColumn::getPosition)
                .containsExactly(
                        tuple("To do", 0),
                        tuple("In progress", 1),
                        tuple("Done", 2));
    }

    // Open views of the board reload on these (BoardEvents); a change without one leaves them stale.
    @Test
    void changesTellTheBoardsViewers() {
        Task task = addTask("A", todo);
        boardService.renameBoard(board.getId(), ALICE, "Renamed");
        BoardColumn later = boardService.addColumn(board.getId(), ALICE, "Later", null);
        boardService.updateColumn(later.getId(), ALICE, new BoardDtos.ColumnPatch("Soon", false, null, null));
        boardService.moveTask(task.getId(), doing.getId(), 0, ALICE);
        boardService.deleteColumn(later.getId(), ALICE, null, false);

        assertThat(events.stream(BoardChanged.class)).hasSize(5).allMatch(e -> e.boardId() == board.getId());
        assertThat(events.stream(BoardShareEnded.class)).isEmpty();

        boardService.deleteBoard(board.getId(), ALICE);
        assertThat(events.stream(BoardShareEnded.class)).containsExactly(new BoardShareEnded(board.getId()));
    }

    @Test
    void moveWithinColumnReorders() {
        Task a = addTask("A", todo);
        addTask("B", todo);
        addTask("C", todo);

        boardService.moveTask(a.getId(), todo.getId(), 2, ALICE);

        assertThat(titlesIn(todo)).containsExactly("B", "C", "A");
    }

    @Test
    void moveToOtherColumnRenumbersBoth() {
        addTask("A", todo);
        Task b = addTask("B", todo);
        addTask("C", todo);
        addTask("X", doing);
        addTask("Y", doing);

        boardService.moveTask(b.getId(), doing.getId(), 1, ALICE);

        assertThat(titlesIn(todo)).containsExactly("A", "C");
        assertThat(titlesIn(doing)).containsExactly("X", "B", "Y");
    }

    @Test
    void movePositionBeyondEndPutsTaskLast() {
        Task a = addTask("A", todo);
        addTask("X", doing);

        boardService.moveTask(a.getId(), doing.getId(), 99, ALICE);

        assertThat(titlesIn(doing)).containsExactly("X", "A");
    }

    @Test
    void cannotMoveIntoColumnOfOtherUser() {
        User bob = userRepository.save(new User("Bob", BOB));
        BoardColumn bobsColumn = columnRepository.findByBoardOrderByPosition(boardService.createDefaultBoard(bob)).get(0);
        Task a = addTask("A", todo);

        assertThatThrownBy(() -> boardService.moveTask(a.getId(), bobsColumn.getId(), 0, ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThat(titlesIn(todo)).containsExactly("A");
    }

    @Test
    void cannotMoveTaskOfOtherUser() {
        Task a = addTask("A", todo);

        assertThatThrownBy(() -> boardService.moveTask(a.getId(), doing.getId(), 0, BOB))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void cannotMoveToOtherBoard() {
        User alice = userRepository.findByEmail(ALICE).orElseThrow();
        BoardColumn otherBoardColumn = columnRepository
                .findByBoardOrderByPosition(boardService.createDefaultBoard(alice)).get(0);
        Task a = addTask("A", todo);

        assertThatThrownBy(() -> boardService.moveTask(a.getId(), otherBoardColumn.getId(), 0, ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void deletingNonEmptyColumnNeedsAChoice() {
        addTask("A", todo);

        assertThatThrownBy(() -> boardService.deleteColumn(todo.getId(), ALICE, null, false))
                .satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
        assertThat(columnRepository.findById(todo.getId())).isPresent();
    }

    @Test
    void deleteColumnMovesTasksToEndOfTarget() {
        addTask("A", todo);
        addTask("B", todo);
        addTask("X", done);

        boardService.deleteColumn(todo.getId(), ALICE, done.getId(), false);

        assertThat(titlesIn(done)).containsExactly("X", "A", "B");
        assertThat(columnRepository.findByBoardOrderByPosition(board))
                .extracting(BoardColumn::getName, BoardColumn::getPosition)
                .containsExactly(
                        tuple("In progress", 0),
                        tuple("Done", 1));
    }

    @Test
    void deleteColumnWithTasks() {
        Task a = addTask("A", todo);

        boardService.deleteColumn(todo.getId(), ALICE, null, true);
        em.flush();

        assertThat(taskRepository.findById(a.getId())).isEmpty();
        assertThat(columnRepository.findById(todo.getId())).isEmpty();
    }

    @Test
    void deleteEmptyColumnNeedsNoChoice() {
        boardService.deleteColumn(doing.getId(), ALICE, null, false);

        assertThat(columnRepository.findByBoardOrderByPosition(board))
                .extracting(BoardColumn::getName)
                .containsExactly("To do", "Done");
    }

    @Test
    void cannotMoveTasksIntoDeletedColumnItself() {
        addTask("A", todo);

        assertThatThrownBy(() -> boardService.deleteColumn(todo.getId(), ALICE, todo.getId(), false))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void cannotMoveTasksIntoColumnOfOtherUser() {
        User bob = userRepository.save(new User("Bob", BOB));
        BoardColumn bobsColumn = columnRepository.findByBoardOrderByPosition(boardService.createDefaultBoard(bob)).get(0);
        addTask("A", todo);

        assertThatThrownBy(() -> boardService.deleteColumn(todo.getId(), ALICE, bobsColumn.getId(), false))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void cannotDeleteLastColumn() {
        boardService.deleteColumn(doing.getId(), ALICE, null, false);
        boardService.deleteColumn(done.getId(), ALICE, null, false);

        assertThatThrownBy(() -> boardService.deleteColumn(todo.getId(), ALICE, null, false))
                .satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
    }

    @Test
    void cannotDeleteColumnOfOtherUser() {
        assertThatThrownBy(() -> boardService.deleteColumn(todo.getId(), BOB, null, true))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void addColumnTrimsNameAndAppends() {
        BoardColumn added = boardService.addColumn(board.getId(), ALICE, "  Review ", null);

        assertThat(added.getName()).isEqualTo("Review");
        assertThat(added.getPosition()).isEqualTo(3);
    }

    @Test
    void columnNameMustNotBeBlankOrTooLong() {
        assertThatThrownBy(() -> boardService.addColumn(board.getId(), ALICE, "   ", null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> boardService.updateColumn(todo.getId(), ALICE, patchName("x".repeat(51))))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    private static BoardDtos.ColumnPatch patchName(String name) {
        return new BoardDtos.ColumnPatch(name, false, null, null);
    }

    private static BoardDtos.ColumnPatch patchColor(String color) {
        return new BoardDtos.ColumnPatch(null, true, color, null);
    }

    private static BoardDtos.ColumnPatch patchPosition(int position) {
        return new BoardDtos.ColumnPatch(null, false, null, position);
    }

    private List<String> columnNames() {
        em.flush();
        em.clear();
        List<BoardColumn> columns = columnRepository.findByBoardOrderByPosition(board);
        for (int i = 0; i < columns.size(); i++) {
            assertThat(columns.get(i).getPosition()).as("positions have no gaps").isEqualTo(i);
        }
        return columns.stream().map(BoardColumn::getName).toList();
    }

    @Test
    void moveColumnForwardShiftsColumnsInBetween() {
        boardService.addColumn(board.getId(), ALICE, "Review", null);
        BoardColumn review = columnRepository.findByBoardOrderByPosition(board).get(3);

        boardService.updateColumn(review.getId(), ALICE, patchPosition(0));

        assertThat(columnNames()).containsExactly("Review", "To do", "In progress", "Done");
    }

    @Test
    void moveColumnBackward() {
        boardService.updateColumn(todo.getId(), ALICE, patchPosition(1));

        assertThat(columnNames()).containsExactly("In progress", "To do", "Done");
    }

    @Test
    void moveColumnBeyondEndPutsItLast() {
        boardService.updateColumn(todo.getId(), ALICE, patchPosition(99));

        assertThat(columnNames()).containsExactly("In progress", "Done", "To do");
    }

    @Test
    void movingColumnKeepsItsTasks() {
        addTask("A", todo);

        boardService.updateColumn(todo.getId(), ALICE, patchPosition(2));

        assertThat(titlesIn(todo)).containsExactly("A");
    }

    @Test
    void cannotUpdateColumnOfOtherUser() {
        assertThatThrownBy(() -> boardService.updateColumn(todo.getId(), BOB, patchName("Hijacked")))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void patchOnlyChangesGivenFields() {
        boardService.updateColumn(todo.getId(), ALICE, patchName("Backlog"));
        em.flush();
        em.clear();

        BoardColumn column = columnRepository.findById(todo.getId()).orElseThrow();
        assertThat(column.getName()).isEqualTo("Backlog");
        assertThat(column.getColor()).isEqualTo("#64748b");
        assertThat(column.getPosition()).isEqualTo(0);
    }

    @Test
    void colorIsStoredLowerCaseAndCanBeRemoved() {
        boardService.updateColumn(todo.getId(), ALICE, patchColor("#AABBCC"));
        assertThat(todo.getColor()).isEqualTo("#aabbcc");

        boardService.updateColumn(todo.getId(), ALICE, patchColor(null));
        assertThat(todo.getColor()).isNull();
    }

    @Test
    void invalidColorIsRejected() {
        assertThatThrownBy(() -> boardService.updateColumn(todo.getId(), ALICE, patchColor("red")))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> boardService.addColumn(board.getId(), ALICE, "Review", "#12345"))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void cannotReadBoardOfOtherUser() {
        assertThatThrownBy(() -> boardService.getBoard(board.getId(), BOB))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void boardDetailListsTasksInColumnOrder() {
        addTask("D", done);
        addTask("A", todo);
        addTask("B", todo);
        em.flush();
        em.clear();

        BoardDtos.BoardDetail detail = boardService.getBoard(board.getId(), ALICE);

        assertThat(detail.columns()).extracting(BoardDtos.ColumnDto::name)
                .containsExactly("To do", "In progress", "Done");
        assertThat(detail.tasks()).extracting(t -> t.title()).containsExactly("A", "B", "D");
    }

    @Test
    void createdBoardHasDefaultColumnsAndIsListedLast() {
        Board second = boardService.createBoard(ALICE, "  Side project ");

        assertThat(second.getName()).isEqualTo("Side project");
        assertThat(columnRepository.findByBoardOrderByPosition(second))
                .extracting(BoardColumn::getName)
                .containsExactly("To do", "In progress", "Done");
        assertThat(boardService.boardsOf(ALICE))
                .extracting(Board::getName)
                .containsExactly("My board", "Side project");
    }

    @Test
    void boardNameMustNotBeBlank() {
        assertThatThrownBy(() -> boardService.createBoard(ALICE, " "))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void deleteBoardRemovesItsColumnsAndTasksOnly() {
        Board other = boardService.createBoard(ALICE, "Other");
        BoardColumn otherColumn = columnRepository.findByBoardOrderByPosition(other).get(0);
        Task kept = addTask("Kept", otherColumn);
        Task gone = addTask("Gone", todo);
        addTask("Gone too", done);

        boardService.deleteBoard(board.getId(), ALICE);
        em.flush();
        em.clear();

        assertThat(boardRepository.findById(board.getId())).isEmpty();
        assertThat(columnRepository.findById(todo.getId())).isEmpty();
        assertThat(taskRepository.findById(gone.getId())).isEmpty();
        assertThat(taskRepository.findById(kept.getId())).isPresent();
        assertThat(boardService.boardsOf(ALICE)).extracting(Board::getName).containsExactly("Other");
    }

    @Test
    void lastBoardCanBeDeleted() {
        boardService.deleteBoard(board.getId(), ALICE);

        assertThat(boardService.boardsOf(ALICE)).isEmpty();
    }

    @Test
    void cannotDeleteBoardOfOtherUser() {
        userRepository.save(new User("Bob", BOB));

        assertThatThrownBy(() -> boardService.deleteBoard(board.getId(), BOB))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThat(boardRepository.findById(board.getId())).isPresent();
    }

    @Test
    void boardsGetSlugsUniquePerUser() {
        Board second = boardService.createBoard(ALICE, "My Board!");
        Board third = boardService.createBoard(ALICE, "my board");
        userRepository.save(new User("Bob", BOB));
        Board bobs = boardService.createBoard(BOB, "My board");

        assertThat(board.getSlug()).isEqualTo("my-board");
        assertThat(second.getSlug()).isEqualTo("my-board-2");
        assertThat(third.getSlug()).isEqualTo("my-board-3");
        assertThat(bobs.getSlug()).isEqualTo("my-board");
    }

    @Test
    void missingSlugsAreFilledInWithoutClashes() {
        Board other = boardService.createBoard(ALICE, "My board");
        board.setSlug(null);
        other.setSlug(null);
        em.flush();

        int filled = boardService.fillMissingSlugs();
        em.flush();
        em.clear();

        assertThat(filled).isEqualTo(2);
        assertThat(boardService.boardsOf(ALICE))
                .extracting(Board::getSlug)
                .containsExactly("my-board", "my-board-2");
        assertThat(boardService.fillMissingSlugs()).isZero();
    }

    @Test
    void taskTemplateIsStoredTrimmedAndShownInTheBoard() {
        assertThat(boardService.getBoard(board.getId(), ALICE).taskTemplate()).isNull();
        String stored = boardService.setTaskTemplate(board.getId(), ALICE, "\n## Steps\n- [ ] Cover letter\n\n");
        assertThat(stored).isEqualTo("## Steps\n- [ ] Cover letter");
        em.flush();
        em.clear();
        assertThat(boardService.getBoard(board.getId(), ALICE).taskTemplate()).isEqualTo(stored);
    }

    @Test
    void blankTaskTemplateRemovesIt() {
        boardService.setTaskTemplate(board.getId(), ALICE, "- [ ] Something");
        assertThat(boardService.setTaskTemplate(board.getId(), ALICE, "  \n ")).isNull();
        assertThat(boardService.setTaskTemplate(board.getId(), ALICE, null)).isNull();
        assertThat(boardService.getBoard(board.getId(), ALICE).taskTemplate()).isNull();
    }

    @Test
    void taskTemplateHasTheDescriptionLimit() {
        String max = "a".repeat(TaskController.MAX_DESCRIPTION_LENGTH);
        assertThat(boardService.setTaskTemplate(board.getId(), ALICE, max)).hasSize(TaskController.MAX_DESCRIPTION_LENGTH);
        assertThatThrownBy(() -> boardService.setTaskTemplate(board.getId(), ALICE, max + "a"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void cannotSetTaskTemplateOfOtherUser() {
        userRepository.save(new User("Bob", BOB));
        assertThatThrownBy(() -> boardService.setTaskTemplate(board.getId(), BOB, "x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Board not found");
        assertThat(boardService.getBoard(board.getId(), ALICE).taskTemplate()).isNull();
    }

    @Test
    void renamingMovesTheSlugToTheNewName() {
        boardService.createBoard(ALICE, "Applications");

        Board renamed = boardService.renameBoard(board.getId(), ALICE, "  Applications  ");

        assertThat(renamed.getName()).isEqualTo("Applications");
        assertThat(renamed.getSlug()).isEqualTo("applications-2");
        em.flush();
        em.clear();
        assertThat(boardService.getBoard(board.getId(), ALICE).slug()).isEqualTo("applications-2");
    }

    @Test
    void renamingToTheSameSlugKeepsIt() {
        Board second = boardService.createBoard(ALICE, "My board");
        assertThat(second.getSlug()).isEqualTo("my-board-2");

        assertThat(boardService.renameBoard(second.getId(), ALICE, "My Board!").getSlug()).isEqualTo("my-board-2");
        assertThat(boardService.renameBoard(board.getId(), ALICE, "MY BOARD").getSlug()).isEqualTo("my-board");
    }

    @Test
    void renameChecksTheName() {
        assertThatThrownBy(() -> boardService.renameBoard(board.getId(), ALICE, "  "))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> boardService.renameBoard(board.getId(), ALICE, "a".repeat(51)))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThat(boardService.renameBoard(board.getId(), ALICE, "a".repeat(50)).getName()).hasSize(50);
    }

    @Test
    void cannotRenameBoardOfOtherUser() {
        userRepository.save(new User("Bob", BOB));

        assertThatThrownBy(() -> boardService.renameBoard(board.getId(), BOB, "Mine now"))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        em.flush();
        em.clear();
        assertThat(boardService.getBoard(board.getId(), ALICE).name()).isEqualTo("My board");
    }

    @Test
    void boardDetailCarriesTheSlug() {
        assertThat(boardService.getBoard(board.getId(), ALICE).slug()).isEqualTo("my-board");
    }

    @Test
    void taskDescriptionIsNotLimitedTo255Characters() {
        Task task = addTask("Long", todo);
        String description = "a".repeat(5_000);
        task.setDescription(description);
        em.flush();
        em.clear();
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getDescription()).isEqualTo(description);
    }
}
