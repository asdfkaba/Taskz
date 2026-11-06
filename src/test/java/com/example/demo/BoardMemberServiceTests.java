package com.example.demo;

import com.example.demo.board.*;
import com.example.demo.board.BoardDtos.BoardSummary;
import com.example.demo.board.BoardMemberService.MemberDto;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invite links, joining, and what members may do (against H2, with real queries). */
@DataJpaTest
@Import({BoardMemberService.class, BoardService.class, RuleEngine.class, LabelService.class, TaskNoteService.class, TaskHistoryService.class})
class BoardMemberServiceTests {

    static final String ALICE = "alice@example.com"; // owner
    static final String BOB = "bob@example.com";     // joins
    static final String EVE = "eve@example.com";     // never invited

    @Autowired BoardMemberService members;
    @Autowired BoardService boardService;
    @Autowired LabelService labelService;
    @Autowired TaskNoteService notes;
    @Autowired UserRepository userRepository;
    @Autowired BoardColumnRepository columnRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired EntityManager em;

    Board board;
    Task task;
    User bob;

    @BeforeEach
    void setUp() {
        board = boardService.createDefaultBoard(userRepository.save(new User("Alice", ALICE)));
        bob = userRepository.save(new User("Bob", BOB));
        userRepository.save(new User("Eve", EVE));
        task = new Task();
        task.setTitle("Bewerbung");
        task.setUser(board.getUser());
        task.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        taskRepository.save(task);
    }

    private String inviteToken() {
        return members.createInvite(board.getId(), ALICE).path().substring("/join/".length());
    }

    private void join(String email) {
        members.join(inviteToken(), email);
        em.flush();
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    @Test
    void aMemberOpensAndChangesTheBoard() {
        String token = inviteToken();
        assertThat(members.invite(token, BOB)).isEqualTo(new BoardMemberService.InviteInfo("My board", "Alice", board.getUser().getId(), false));

        members.join(token, BOB);

        assertThat(boardService.summariesOf(BOB)).contains(new BoardSummary(board.getId(), "My board", "my-board", false, "Alice", board.getUser().getId()));
        assertThat(boardService.getBoard(board.getId(), BOB).tasks()).hasSize(1);
        assertThat(boardService.findTask(task.getId(), BOB)).isEqualTo(task);
        boardService.addColumn(board.getId(), BOB, "Later", null);
        labelService.addLabel(board.getId(), BOB, "Urgent", "#ef4444");
        assertThat(members.members(board.getId(), BOB)).extracting(MemberDto::name, MemberDto::owner)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Alice", true), org.assertj.core.groups.Tuple.tuple("Bob", false));
    }

    @Test
    void othersCanNeitherOpenTheBoardNorItsTasks() {
        join(BOB);
        assertStatus(catchThrowable(() -> boardService.findBoard(board.getId(), EVE)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> boardService.findTask(task.getId(), EVE)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> members.members(board.getId(), EVE)), HttpStatus.NOT_FOUND);
        assertThat(boardService.summariesOf(EVE)).isEmpty();
    }

    @Test
    void onlyTheOwnerDeletesInvitesAndRemoves() {
        join(BOB);
        assertStatus(catchThrowable(() -> boardService.deleteBoard(board.getId(), BOB)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> members.createInvite(board.getId(), BOB)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> members.removeMember(board.getId(), bob.getId(), BOB)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> boardService.findOwnBoard(board.getId(), BOB)), HttpStatus.NOT_FOUND);
    }

    @Test
    void aLinkWorksOnce() {
        String token = inviteToken();
        members.join(token, BOB);
        assertStatus(catchThrowable(() -> members.join(token, EVE)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> members.invite(token, EVE)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> boardService.findBoard(board.getId(), EVE)), HttpStatus.NOT_FOUND);
    }

    // The owner trying their own link doesn't use it up.
    @Test
    void openingAsOwnerKeepsTheLink() {
        String token = inviteToken();
        assertThat(members.invite(token, ALICE).member()).isTrue();
        assertThat(members.join(token, ALICE)).isEqualTo(board.getId());
        members.join(token, BOB);
        assertThat(boardService.findBoard(board.getId(), BOB)).isEqualTo(board);
    }

    @Test
    void expiredAndUnknownLinksDontWork() {
        String token = inviteToken();
        em.createQuery("update BoardInvite i set i.expires = :past").setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
        assertStatus(catchThrowable(() -> members.join(token, BOB)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> members.join("nope", BOB)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> members.join("x".repeat(500), BOB)), HttpStatus.NOT_FOUND);
    }

    @Test
    void leavingAndBeingRemovedEndAccess() {
        join(BOB);
        members.leave(board.getId(), BOB);
        assertStatus(catchThrowable(() -> boardService.findBoard(board.getId(), BOB)), HttpStatus.NOT_FOUND);

        join(BOB);
        members.removeMember(board.getId(), bob.getId(), ALICE);
        assertStatus(catchThrowable(() -> boardService.findBoard(board.getId(), BOB)), HttpStatus.NOT_FOUND);

        assertStatus(catchThrowable(() -> members.leave(board.getId(), ALICE)), HttpStatus.CONFLICT);
    }

    @Test
    void deletingTheBoardDeletesItsMembers() {
        join(BOB);
        inviteToken();
        boardService.deleteBoard(board.getId(), ALICE);
        em.flush();
        assertThat(boardService.summariesOf(BOB)).isEmpty();
    }

    // Bob has a board called "My board" too: the joined one gets its own address.
    @Test
    void aJoinedBoardWithTheSameNameGetsItsOwnSlug() {
        boardService.createDefaultBoard(bob);
        join(BOB);
        assertThat(boardService.summariesOf(BOB)).extracting(BoardSummary::slug).containsExactly("my-board", "my-board-2");
        assertThat(boardService.summaryOf(board.getId(), BOB).slug()).isEqualTo("my-board-2");
    }

    @Test
    void onlyAuthorsEditNotesAndOwnersDeleteThemToo() {
        join(BOB);
        NoteDto alices = notes.addNote(task.getId(), ALICE, "From Alice", null);
        NoteDto bobs = notes.addNote(task.getId(), BOB, "From Bob", null);

        assertThat(notes.notesOf(task.getId(), BOB)).extracting(NoteDto::text, NoteDto::canEdit, NoteDto::canDelete)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("From Alice", false, false),
                        org.assertj.core.groups.Tuple.tuple("From Bob", true, true));
        assertStatus(catchThrowable(() -> notes.updateNote(alices.id(), BOB, "Changed", null)), HttpStatus.FORBIDDEN);
        assertStatus(catchThrowable(() -> notes.deleteNote(alices.id(), BOB)), HttpStatus.FORBIDDEN);
        assertStatus(catchThrowable(() -> notes.updateNote(bobs.id(), ALICE, "Changed", null)), HttpStatus.FORBIDDEN);

        notes.deleteNote(bobs.id(), ALICE);
        assertThat(notes.notesOf(task.getId(), ALICE)).extracting(NoteDto::text).containsExactly("From Alice");
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return org.assertj.core.api.Assertions.catchThrowable(call);
    }
}
