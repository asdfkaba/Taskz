package com.example.demo.notification;

import com.example.demo.board.Board;
import com.example.demo.board.BoardColumnRepository;
import com.example.demo.board.BoardService;
import com.example.demo.notification.NotificationService.NotificationDto;
import com.example.demo.task.RuleEngine;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({NotificationService.class, BoardService.class, RuleEngine.class, TaskHistoryService.class})
class NotificationServiceTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired
    NotificationService service;
    @Autowired
    BoardService boardService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    NotificationRepository repository;
    @Autowired
    EntityManager em;

    User alice;
    User bob;
    Board board;
    Task task;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(new User("Alice", ALICE));
        bob = userRepository.save(new User("Bob", BOB));
        board = boardService.createDefaultBoard(alice);
        task = new Task("Acme", null, null, null);
        task.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        taskRepository.save(task);
    }

    private List<NotificationDto> listOf(String email) {
        return service.notificationsOf(email, boardService.summariesOf(email)).items();
    }

    @Test
    void aNotificationLinksToItsTaskOrBoardOrNothing() {
        service.notify(List.of(alice), "About nothing", null, null);
        service.notify(List.of(alice), "About the board", board, null);
        service.notify(List.of(alice), "About the task", null, task);

        List<NotificationDto> items = listOf(ALICE);
        assertThat(items).extracting(NotificationDto::text).containsExactly("About the task", "About the board", "About nothing");
        assertThat(items.get(0).boardSlug()).isEqualTo(board.getSlug());
        assertThat(items.get(0).taskId()).isEqualTo(task.getId());
        assertThat(items.get(0).taskTitle()).isEqualTo("Acme");
        assertThat(items.get(1).boardSlug()).isEqualTo(board.getSlug());
        assertThat(items.get(1).taskId()).isNull();
        assertThat(items.get(2).boardName()).isNull();
        assertThat(items.get(2).boardSlug()).isNull();
        assertThat(service.notificationsOf(ALICE, List.of()).unseen()).isEqualTo(3);
        assertThat(listOf(BOB)).isEmpty();
    }

    @Test
    void withoutAccessToTheBoardThereIsNoLink() {
        service.notify(List.of(bob), "Not yours", null, task);
        NotificationDto item = listOf(BOB).get(0);
        assertThat(item.boardName()).isEqualTo(board.getName());
        assertThat(item.boardSlug()).isNull();
        assertThat(item.taskId()).isNull();
    }

    @Test
    void seenClearsTheCountButNotTheUnreadMarks() {
        service.notify(List.of(alice), "One", null, null);
        service.notify(List.of(alice), "Two", null, null);
        service.markAllSeen(ALICE);
        service.notify(List.of(alice), "Three", null, null);
        em.clear();

        assertThat(service.notificationsOf(ALICE, List.of()).unseen()).isEqualTo(1);
        assertThat(listOf(ALICE)).noneMatch(NotificationDto::read);
    }

    @Test
    void readOneOrAllOnlyOfYourOwn() {
        service.notify(List.of(alice), "One", null, null);
        service.notify(List.of(alice), "Two", null, null);
        service.notify(List.of(bob), "Bob's", null, null);
        int first = listOf(ALICE).get(1).id();
        int bobs = listOf(BOB).get(0).id();

        service.markRead(first, ALICE);
        assertThat(listOf(ALICE)).extracting(NotificationDto::read).containsExactly(false, true);
        assertThat(service.notificationsOf(ALICE, List.of()).unseen()).isEqualTo(1);
        assertThatThrownBy(() -> service.markRead(bobs, ALICE))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        service.markAllRead(ALICE);
        em.clear();
        assertThat(listOf(ALICE)).allMatch(NotificationDto::read);
        assertThat(service.notificationsOf(ALICE, List.of()).unseen()).isZero();
        assertThat(listOf(BOB)).noneMatch(NotificationDto::read);
        assertThat(service.notificationsOf(BOB, List.of()).unseen()).isEqualTo(1);
    }

    @Test
    void longTextIsCutAndDeletingTheBoardDeletesItsNotifications() {
        service.notify(List.of(alice), "x".repeat(600), null, task);
        service.notify(List.of(alice), "Stays", null, null);
        assertThat(listOf(ALICE).get(1).text()).hasSize(500).endsWith("…");

        em.flush();
        boardService.deleteBoard(board.getId(), ALICE);
        em.flush();
        assertThat(listOf(ALICE)).extracting(NotificationDto::text).containsExactly("Stays");
    }
}
