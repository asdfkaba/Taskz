package com.example.demo;

import com.example.demo.board.*;
import com.example.demo.board.ApiTokenService.NewToken;
import com.example.demo.notification.NotificationService;
import com.example.demo.task.*;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Creating, listing and revoking API tokens, and the one-board limit (against H2, with real queries). */
@DataJpaTest
@Import({ApiTokenService.class, BoardMemberService.class, BoardService.class, RuleEngine.class, NotificationService.class,
        LabelService.class, TaskNoteService.class, TaskHistoryService.class})
class ApiTokenServiceTests {

    static final String ALICE = "alice@example.com"; // owner
    static final String BOB = "bob@example.com";     // member
    static final String EVE = "eve@example.com";     // neither
    static final List<String> READ = List.of("tasks:read");
    static final List<String> WRITE = List.of("tasks:update", "tasks:create");

    @Autowired ApiTokenService tokens;
    @Autowired BoardMemberService members;
    @Autowired BoardService boardService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    Board board;
    Board otherBoard;

    @BeforeEach
    void setUp() {
        User alice = userRepository.save(new User("Alice", ALICE));
        board = boardService.createDefaultBoard(alice);
        otherBoard = boardService.createBoard(ALICE, "Other");
        userRepository.save(new User("Bob", BOB));
        userRepository.save(new User("Eve", EVE));
        String invite = members.createInvite(board.getId(), ALICE).path().substring("/join/".length());
        members.join(invite, BOB);
        em.flush();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    @Test
    void aTokenIsShownOnceAndStoredAsHash() {
        NewToken created = tokens.create(board.getId(), ALICE, "  Bewerbungen  ", WRITE, null);

        assertThat(created.token()).startsWith("taskz_");
        assertThat(created.info().name()).isEqualTo("Bewerbungen");
        assertThat(tokens.list(ALICE)).singleElement()
                .satisfies(t -> {
                    assertThat(t.boardId()).isEqualTo(board.getId());
                    assertThat(t.boardName()).isEqualTo("My board");
                    assertThat(t.scopes()).containsExactly("tasks:create", "tasks:update");
                    assertThat(t.expiresAt()).isNull();
                    assertThat(t.lastUsedAt()).isNull();
                });
        em.flush();
        Object stored = em.createNativeQuery("select token_hash from api_token").getSingleResult();
        assertThat(stored).isNotEqualTo(created.token());

        ApiTokenAuthentication auth = tokens.authenticate(created.token()).orElseThrow();
        assertThat(auth.getName()).isEqualTo(ALICE);
        assertThat(auth.boardId()).isEqualTo(board.getId());
        assertThat(auth.scopes()).containsExactlyInAnyOrder(ApiScope.TASKS_CREATE, ApiScope.TASKS_UPDATE);
    }

    @Test
    void aTokenTellsItsBoardColumnsAndLabels() {
        NewToken created = tokens.create(board.getId(), ALICE, "write only", WRITE, null);
        ApiTokenAuthentication auth = tokens.authenticate(created.token()).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(auth);

        ApiTokenService.TokenAccess access = tokens.access(auth);

        assertThat(access.boardId()).isEqualTo(board.getId());
        assertThat(access.boardName()).isEqualTo("My board");
        assertThat(access.scopes()).containsExactly("tasks:create", "tasks:update");
        assertThat(access.expiresAt()).isNull();
        assertThat(access.columns()).extracting(BoardDtos.ColumnDto::name).containsExactly("To do", "In progress", "Done");
        assertThat(access.labels()).isNotNull();
    }

    @Test
    void unknownTokensDontWork() {
        assertThat(tokens.authenticate(null)).isEmpty();
        assertThat(tokens.authenticate("taskz_nope")).isEmpty();
        assertThat(tokens.authenticate("something-else")).isEmpty();
    }

    @Test
    void namesAndRightsAreChecked() {
        assertThatThrownBy(() -> tokens.create(board.getId(), ALICE, " ", READ, null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> tokens.create(board.getId(), ALICE, "x".repeat(51), READ, null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> tokens.create(board.getId(), ALICE, "none", List.of(), null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> tokens.create(null, ALICE, "no board", READ, null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> tokens.create(board.getId(), ALICE, "unknown", List.of("tasks:read", "boards:delete"), null))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> tokens.create(board.getId(), ALICE, "too short", READ, 0))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> tokens.create(board.getId(), ALICE, "too long", READ, 366))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void anExpiredTokenStopsWorking() {
        NewToken created = tokens.create(board.getId(), ALICE, "a month", READ, 30);
        assertThat(created.info().expiresAt()).isBetween(Instant.now().plusSeconds(29 * 86400), Instant.now().plusSeconds(31 * 86400));
        assertThat(tokens.authenticate(created.token())).isPresent();

        em.flush();
        em.createNativeQuery("update api_token set expires_at = ?").setParameter(1, Instant.now().minusSeconds(1)).executeUpdate();
        em.clear();

        assertThat(tokens.authenticate(created.token())).isEmpty();
    }

    @Test
    void usingATokenIsNoted() {
        NewToken created = tokens.create(board.getId(), ALICE, "script", READ, null);

        tokens.authenticate(created.token());
        em.flush();
        em.clear();

        assertThat(tokens.list(ALICE)).singleElement()
                .satisfies(t -> assertThat(t.lastUsedAt()).isBetween(Instant.now().minusSeconds(60), Instant.now()));
    }

    @Test
    void everyoneHasTheirOwnTokensOnlyForTheirBoards() {
        tokens.create(board.getId(), ALICE, "alice", READ, null);
        tokens.create(otherBoard.getId(), ALICE, "alice other", READ, null);
        NewToken bobs = tokens.create(board.getId(), BOB, "bob", READ, null);

        assertThat(tokens.list(ALICE)).extracting(ApiTokenService.TokenDto::name).containsExactly("alice", "alice other");
        assertThat(tokens.list(BOB)).extracting(ApiTokenService.TokenDto::name).containsExactly("bob");
        assertThat(tokens.list(EVE)).isEmpty();
        assertThatThrownBy(() -> tokens.create(board.getId(), EVE, "eve", READ, null))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> tokens.create(otherBoard.getId(), BOB, "not bob's", READ, null))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        // Only the creator revokes.
        assertThatThrownBy(() -> tokens.revoke(bobs.info().id(), ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));

        tokens.revoke(bobs.info().id(), BOB);

        assertThat(tokens.authenticate(bobs.token())).isEmpty();
    }

    @Test
    void aTokenOnlyReachesItsBoard() {
        NewToken created = tokens.create(board.getId(), ALICE, "only this board", READ, null);
        SecurityContextHolder.getContext().setAuthentication(tokens.authenticate(created.token()).orElseThrow());

        assertThat(boardService.canAccess(board, ALICE)).isTrue();
        assertThat(boardService.canAccess(otherBoard, ALICE)).isFalse();
        assertThatThrownBy(() -> boardService.findBoard(otherBoard.getId(), ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> boardService.findOwnBoard(otherBoard.getId(), ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void aTokenStopsWorkingWhenItsCreatorLeaves() {
        NewToken created = tokens.create(board.getId(), BOB, "bob", READ, null);
        members.leave(board.getId(), BOB);
        em.flush();
        SecurityContextHolder.getContext().setAuthentication(tokens.authenticate(created.token()).orElseThrow());

        assertThat(boardService.canAccess(board, BOB)).isFalse();
    }
}
