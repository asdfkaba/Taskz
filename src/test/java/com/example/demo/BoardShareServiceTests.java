package com.example.demo;

import com.example.demo.task.RuleEngine;
import com.example.demo.notification.NotificationService;
import com.example.demo.board.*;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({BoardService.class, RuleEngine.class, NotificationService.class, BoardShareService.class, TaskHistoryService.class})
@RecordApplicationEvents
class BoardShareServiceTests {

    @Autowired
    ApplicationEvents events;

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired BoardService boardService;
    @Autowired BoardShareService shareService;
    @Autowired UserRepository userRepository;
    @Autowired BoardColumnRepository columnRepository;
    @Autowired BoardShareRepository shareRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired EntityManager em;

    Board board;

    @BeforeEach
    void setUp() {
        User alice = userRepository.save(new User("Alice", ALICE));
        userRepository.save(new User("Bob", BOB));
        board = boardService.createDefaultBoard(alice);

        BoardColumn todo = columnRepository.findByBoardOrderByPosition(board).get(0);
        Task task = new Task();
        task.setTitle("Shared task");
        task.setUser(alice);
        task.setBoardColumn(todo);
        taskRepository.save(task);
    }

    private static void assertNotFound(Throwable e) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String refresh(String token) {
        em.flush();
        em.clear();
        return token;
    }

    @Test
    void boardIsNotSharedByDefault() {
        assertThat(shareService.getShare(board.getId(), ALICE)).isEmpty();
    }

    @Test
    void sharedBoardShowsColumnsAndTasks() {
        String token = refresh(shareService.createShare(board.getId(), ALICE).getToken().toString());

        BoardDtos.SharedBoard shared = shareService.sharedBoard(token);

        assertThat(shared.name()).isEqualTo("My board");
        assertThat(shared.columns()).extracting(BoardDtos.ColumnDto::name)
                .containsExactly("To do", "In progress", "Done");
        assertThat(shared.tasks()).extracting(t -> t.title()).containsExactly("Shared task");
        // Shared boards don't include attachments, not even how many there are.
        assertThat(shared.tasks()).extracting(t -> t.attachments()).containsOnlyNulls();
        assertThat(shareService.getShare(board.getId(), ALICE)).isPresent();
    }

    @Test
    void creatingAgainReplacesTheLink() {
        String old = refresh(shareService.createShare(board.getId(), ALICE).getToken().toString());
        String renewed = refresh(shareService.createShare(board.getId(), ALICE).getToken().toString());

        assertThat(renewed).isNotEqualTo(old);
        assertThatThrownBy(() -> shareService.sharedBoard(old)).satisfies(BoardShareServiceTests::assertNotFound);
        assertThat(shareService.sharedBoard(renewed).name()).isEqualTo("My board");
        assertThat(shareRepository.count()).isEqualTo(1);
        // Viewers of the old link are cut off.
        assertThat(events.stream(BoardShareEnded.class)).hasSize(2);
    }

    @Test
    void revokedLinkStopsWorking() {
        String token = refresh(shareService.createShare(board.getId(), ALICE).getToken().toString());

        shareService.revokeShare(board.getId(), ALICE);
        refresh(token);

        assertThatThrownBy(() -> shareService.sharedBoard(token)).satisfies(BoardShareServiceTests::assertNotFound);
        assertThat(shareService.getShare(board.getId(), ALICE)).isEmpty();
        assertThat(events.stream(BoardShareEnded.class)).contains(new BoardShareEnded(board.getId()));
    }

    @Test
    void unknownOrMalformedTokensAreNotFound() {
        assertThatThrownBy(() -> shareService.sharedBoard("00000000-0000-0000-0000-000000000000"))
                .satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.sharedBoard("not-a-uuid"))
                .satisfies(BoardShareServiceTests::assertNotFound);
    }

    @Test
    void onlyTheOwnerManagesTheLink() {
        shareService.createShare(board.getId(), ALICE);

        assertThatThrownBy(() -> shareService.getShare(board.getId(), BOB)).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.createShare(board.getId(), BOB)).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.revokeShare(board.getId(), BOB)).satisfies(BoardShareServiceTests::assertNotFound);
        assertThat(shareRepository.count()).isEqualTo(1);
    }

    @Test
    void deletingASharedBoardRemovesTheLink() {
        String token = refresh(shareService.createShare(board.getId(), ALICE).getToken().toString());

        boardService.deleteBoard(board.getId(), ALICE);
        em.flush();

        assertThat(shareRepository.count()).isZero();
        assertThatThrownBy(() -> shareService.sharedBoard(token)).satisfies(BoardShareServiceTests::assertNotFound);
    }

    @Test
    void readableLinkWorksAndReportsItsCurrentForm() {
        BoardShare share = shareService.createShare(board.getId(), ALICE);
        String path = ShareLinks.path("my-board", share.getToken());
        refresh(path);

        BoardDtos.SharedBoard shared = shareService.sharedBoard(path);
        assertThat(shared.name()).isEqualTo("My board");
        assertThat(shared.path()).isEqualTo(path).matches("my-board-[A-Za-z0-9_-]{22}");
        // Old links (plain token) show the same board and learn the new form.
        assertThat(shareService.sharedBoard(share.getToken().toString()).path()).isEqualTo(path);
    }

    @Test
    void outdatedSlugStillWorks() {
        BoardShare share = shareService.createShare(board.getId(), ALICE);
        String key = ShareLinks.path("x", share.getToken()).substring(2);
        refresh(key);

        assertThat(shareService.sharedBoard("old-name-" + key).path()).isEqualTo("my-board-" + key);
        assertThat(shareService.sharedBoard(key).name()).isEqualTo("My board");
    }

    @Test
    void wrongKeysAreNotFound() {
        BoardShare share = shareService.createShare(board.getId(), ALICE);
        String path = ShareLinks.path("my-board", share.getToken());
        refresh(path);
        String lastCharChanged = path.substring(0, path.length() - 1) + (path.endsWith("A") ? "B" : "A");

        for (String bad : List.of(lastCharChanged, "my-board-" + "A".repeat(21), "my-board", "", "my-board-!!!!!!!!!!!!!!!!!!!!!!")) {
            assertThatThrownBy(() -> shareService.sharedBoard(bad)).satisfies(BoardShareServiceTests::assertNotFound);
        }
    }
}
