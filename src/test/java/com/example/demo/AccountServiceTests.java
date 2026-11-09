package com.example.demo;

import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.RefreshTokenRepository;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.*;
import com.example.demo.notification.NotificationService;
import com.example.demo.task.*;
import com.example.demo.user.AccountService;
import com.example.demo.user.AccountService.SessionDto;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sessions, export and deleting an account (against H2, with real queries). */
@DataJpaTest
@Import({AccountService.class, ApiTokenService.class, BoardMemberService.class, BoardService.class, RuleEngine.class,
        NotificationService.class, LabelService.class, TaskNoteService.class, TaskHistoryService.class,
        TaskAttachmentService.class, AttachmentStorage.class})
class AccountServiceTests {

    static final String ALICE = "alice@example.com"; // deletes her account
    static final String BOB = "bob@example.com";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void attachments(DynamicPropertyRegistry registry) {
        registry.add("attachments.dir", () -> dir.toString());
    }

    @Autowired AccountService account;
    @Autowired BoardService boardService;
    @Autowired BoardMemberService members;
    @Autowired TaskNoteService notes;
    @Autowired TaskAttachmentService attachments;
    @Autowired TaskHistoryService history;
    @Autowired ApiTokenService apiTokens;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired BoardColumnRepository columnRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired EntityManager em;

    User alice;
    User bob;
    Board alicesBoard;
    Board bobsBoard;
    Task alicesTask;
    Task taskOnBobsBoard; // created by Alice

    @BeforeEach
    void setUp() {
        alice = userRepository.save(new User("Alice", ALICE));
        bob = userRepository.save(new User("Bob", BOB));
        alicesBoard = boardService.createDefaultBoard(alice);
        bobsBoard = boardService.createDefaultBoard(bob);
        alicesTask = newTask(alicesBoard, alice, "Alice's own");
        taskOnBobsBoard = newTask(bobsBoard, alice, "Alice on Bob's board");
        join(bobsBoard, ALICE);
        join(alicesBoard, BOB);
    }

    private Task newTask(Board board, User user, String title) {
        Task task = new Task();
        task.setTitle(title);
        task.setUser(user);
        task.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        return taskRepository.save(task);
    }

    private void join(Board board, String email) {
        String path = members.createInvite(board.getId(), board.getUser().getEmail()).path();
        members.join(path.substring("/join/".length()), email);
    }

    private String login(User user) {
        RefreshToken token = new RefreshToken();
        String plain = "plain-" + user.getId() + "-" + refreshTokenRepository.count();
        token.setToken(TokenService.hash(plain));
        token.setUser(user);
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        token.setUserAgent("Firefox");
        refreshTokenRepository.save(token);
        return plain;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @Test
    void listsTheLoginsAndMarksTheCurrentOne() {
        String laptop = login(alice);
        login(alice);
        login(bob);

        List<SessionDto> sessions = account.sessions(ALICE, laptop);

        assertThat(sessions).hasSize(2);
        assertThat(sessions).filteredOn(SessionDto::current).hasSize(1);
        assertThat(account.sessions(ALICE, null)).noneMatch(SessionDto::current);
    }

    @Test
    void endsOneLoginOnlyOfTheOwnAccount() {
        login(alice);
        login(bob);
        Long bobsSession = account.sessions(BOB, null).get(0).id();

        assertThatThrownBy(() -> account.endSession(bobsSession, ALICE))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        account.endSession(bobsSession, BOB);
        assertThat(account.sessions(BOB, null)).isEmpty();
    }

    @Test
    void logsOutAllOtherDevices() {
        String laptop = login(alice);
        login(alice);
        login(bob);

        account.endOtherSessions(ALICE, laptop);
        flushAndClear();

        assertThat(account.sessions(ALICE, laptop)).singleElement().satisfies(s -> assertThat(s.current()).isTrue());
        assertThat(account.sessions(BOB, null)).hasSize(1);
    }

    @Test
    void exportsTheOwnBoardsWithNotesAndTheMemberships() {
        notes.addNote(alicesTask.getId(), ALICE, "My note", null);
        flushAndClear();

        AccountService.Export export = account.export(ALICE, null);

        assertThat(export.user().email()).isEqualTo(ALICE);
        assertThat(export.boards()).singleElement().satisfies(b -> {
            assertThat(b.board().tasks()).extracting(TaskDto::title).containsExactly("Alice's own");
            assertThat(b.notes().get(alicesTask.getId())).extracting(TaskNoteService.NoteDto::text).containsExactly("My note");
        });
        assertThat(export.memberships()).containsExactly(new AccountService.Membership("My board", "Bob"));
    }

    @Test
    void deletingKeepsWhatTheUserWroteOnOtherBoardsWithoutAnAuthor() {
        notes.addNote(taskOnBobsBoard.getId(), ALICE, "Alice's note", null);
        attachments.upload(taskOnBobsBoard.getId(), ALICE, new MockMultipartFile("file", "a.png", "image/png", PNG));
        history.recordCreated(taskOnBobsBoard, ALICE);
        apiTokens.create(alicesBoard.getId(), ALICE, "script", List.of("tasks:read"), null);
        login(alice);
        flushAndClear();

        account.delete(ALICE, " Alice@Example.com ");
        flushAndClear();

        assertThat(userRepository.findByEmail(ALICE)).isEmpty();
        assertThat(boardService.summariesOf(BOB)).extracting(BoardDtos.BoardSummary::id).containsExactly(bobsBoard.getId());
        assertThat(taskRepository.findById(alicesTask.getId())).isEmpty();
        assertThat(taskRepository.findById(taskOnBobsBoard.getId())).hasValueSatisfying(t -> assertThat(t.getUser()).isNull());
        assertThat(notes.notesOf(taskOnBobsBoard.getId(), BOB)).singleElement().satisfies(n -> {
            assertThat(n.text()).isEqualTo("Alice's note");
            assertThat(n.user()).isNull();
        });
        assertThat(attachments.attachmentsOf(taskOnBobsBoard.getId(), BOB)).singleElement().satisfies(a -> assertThat(a.user()).isNull());
        assertThat(history.historyOf(taskRepository.findById(taskOnBobsBoard.getId()).orElseThrow()))
                .allSatisfy(e -> assertThat(e.user()).isNull());
        assertThat(members.members(bobsBoard.getId(), BOB)).extracting(BoardMemberService.MemberDto::name).containsExactly("Bob");
        assertThat(refreshTokenRepository.count()).isZero();
    }

    @Test
    void deletingNeedsTheEmailAsConfirmation() {
        assertThatThrownBy(() -> account.delete(ALICE, "bob@example.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> account.delete(ALICE, null)).isInstanceOf(ResponseStatusException.class);

        assertThat(userRepository.findByEmail(ALICE)).isPresent();
    }
}
