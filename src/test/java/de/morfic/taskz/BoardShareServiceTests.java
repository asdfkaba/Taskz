package de.morfic.taskz;

import de.morfic.taskz.board.BoardDtos.ShareOptions;
import de.morfic.taskz.task.AttachmentStorage;
import de.morfic.taskz.task.RuleEngine;
import de.morfic.taskz.task.TaskAttachmentService;
import de.morfic.taskz.task.TaskAttachmentService.AttachmentDto;
import de.morfic.taskz.task.TaskNoteService;
import de.morfic.taskz.task.TaskNoteService.NoteDto;
import de.morfic.taskz.notification.NotificationService;
import de.morfic.taskz.board.*;
import de.morfic.taskz.task.Task;
import de.morfic.taskz.task.TaskHistoryService;
import de.morfic.taskz.task.TaskRepository;
import de.morfic.taskz.user.User;
import de.morfic.taskz.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@Import({BoardService.class, RuleEngine.class, NotificationService.class, BoardShareService.class, TaskHistoryService.class,
        TaskAttachmentService.class, AttachmentStorage.class, TaskNoteService.class})
@RecordApplicationEvents
class BoardShareServiceTests {

    @Autowired
    ApplicationEvents events;

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void attachments(DynamicPropertyRegistry registry) {
        registry.add("attachments.dir", () -> dir.toString());
    }

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired BoardService boardService;
    @Autowired BoardShareService shareService;
    @Autowired UserRepository userRepository;
    @Autowired BoardColumnRepository columnRepository;
    @Autowired BoardShareRepository shareRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired EntityManager em;
    @Autowired TaskAttachmentService attachmentService;
    @Autowired TaskNoteService noteService;
    @Autowired TaskHistoryService historyService;

    Board board;
    Task task;

    @BeforeEach
    void setUp() {
        User alice = userRepository.save(new User("Alice", ALICE));
        userRepository.save(new User("Bob", BOB));
        board = boardService.createDefaultBoard(alice);

        BoardColumn todo = columnRepository.findByBoardOrderByPosition(board).get(0);
        task = new Task();
        task.setTitle("Shared task");
        task.setUser(alice);
        task.setBoardColumn(todo);
        taskRepository.save(task);
    }

    private AttachmentDto attach(Task to) {
        return attachmentService.upload(to.getId(), ALICE,
                new MockMultipartFile("file", "plan.txt", "text/plain", "Plan".getBytes(StandardCharsets.UTF_8)));
    }

    private String shareWith(ShareOptions options) {
        String token = shareService.createShare(board.getId(), ALICE).getToken().toString();
        shareService.updateOptions(board.getId(), ALICE, options);
        return refresh(token);
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
        assertThat(shared.archivedCount()).isZero();
    }

    @Test
    void sharedBoardCountsArchivedTasksWithoutShowingThem() {
        String token = shareService.createShare(board.getId(), ALICE).getToken().toString();
        Task task = taskRepository.findAll().get(0);
        boardService.archiveTask(task.getId(), ALICE);
        refresh(token);

        BoardDtos.SharedBoard shared = shareService.sharedBoard(token);

        assertThat(shared.tasks()).isEmpty();
        assertThat(shared.archivedCount()).isEqualTo(1);
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

    @Test
    void attachmentsNotesAndHistoryAreNotSharedByDefault() {
        AttachmentDto file = attach(task);
        String token = refresh(shareService.createShare(board.getId(), ALICE).getToken().toString());

        BoardDtos.SharedBoard shared = shareService.sharedBoard(token);
        assertThat(shared.showAttachments()).isFalse();
        assertThat(shared.showNotes()).isFalse();
        assertThat(shared.showHistory()).isFalse();
        assertThatThrownBy(() -> shareService.sharedAttachments(token, task.getId())).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.sharedDownload(token, file.id())).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.sharedNotes(token, task.getId())).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.sharedHistory(token, task.getId())).satisfies(BoardShareServiceTests::assertNotFound);
    }

    @Test
    void sharedAttachmentsCanBeListedAndDownloaded() {
        AttachmentDto file = attach(task);
        String token = shareWith(new ShareOptions(true, null, null));

        BoardDtos.SharedBoard shared = shareService.sharedBoard(token);
        assertThat(shared.showAttachments()).isTrue();
        assertThat(shared.tasks()).extracting(t -> t.attachments()).containsExactly(1);
        assertThat(shareService.sharedAttachments(token, task.getId())).extracting(AttachmentDto::name).containsExactly("plan.txt");
        assertThat(shareService.sharedDownload(token, file.id()).attachment().getFileName()).isEqualTo("plan.txt");
        // Only attachments are shared.
        assertThatThrownBy(() -> shareService.sharedNotes(token, task.getId())).satisfies(BoardShareServiceTests::assertNotFound);
    }

    @Test
    void sharedNotesLeaveOutRemindersAndEditRights() {
        noteService.addNote(task.getId(), ALICE, "Called back", LocalDateTime.now().plusDays(1));
        String token = shareWith(new ShareOptions(null, true, null));

        List<NoteDto> notes = shareService.sharedNotes(token, task.getId());

        assertThat(notes).singleElement().satisfies(note -> {
            assertThat(note.text()).isEqualTo("Called back");
            assertThat(note.user()).isEqualTo("Alice");
            assertThat(note.remindAt()).isNull();
            assertThat(note.canEdit()).isFalse();
            assertThat(note.canDelete()).isFalse();
        });
    }

    @Test
    void sharedHistoryShowsTheTasksChanges() {
        historyService.recordCreated(task, ALICE);
        String token = shareWith(new ShareOptions(null, null, true));

        assertThat(shareService.sharedHistory(token, task.getId())).extracting(e -> e.type()).containsExactly("created");
    }

    @Test
    void sharedTaskDataStaysOnTheBoardAndOffTheArchive() {
        User bob = userRepository.findByEmail(BOB).orElseThrow();
        Board bobsBoard = boardService.createDefaultBoard(bob);
        Task bobsTask = new Task();
        bobsTask.setTitle("Bob's task");
        bobsTask.setUser(bob);
        bobsTask.setBoardColumn(columnRepository.findByBoardOrderByPosition(bobsBoard).get(0));
        taskRepository.save(bobsTask);
        AttachmentDto bobsFile = attachmentService.upload(bobsTask.getId(), BOB,
                new MockMultipartFile("file", "secret.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)));
        AttachmentDto file = attach(task);
        String token = shareWith(new ShareOptions(true, true, true));

        assertThatThrownBy(() -> shareService.sharedAttachments(token, bobsTask.getId())).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.sharedDownload(token, bobsFile.id())).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.sharedHistory(token, bobsTask.getId())).satisfies(BoardShareServiceTests::assertNotFound);

        boardService.archiveTask(task.getId(), ALICE);
        refresh(token);
        assertThatThrownBy(() -> shareService.sharedNotes(token, task.getId())).satisfies(BoardShareServiceTests::assertNotFound);
        assertThatThrownBy(() -> shareService.sharedDownload(token, file.id())).satisfies(BoardShareServiceTests::assertNotFound);
    }

    @Test
    void changingOptionsKeepsTheLinkAndUpdatesViewers() {
        String token = refresh(shareService.createShare(board.getId(), ALICE).getToken().toString());

        BoardShare share = shareService.updateOptions(board.getId(), ALICE, new ShareOptions(true, false, null));

        assertThat(share.getToken().toString()).isEqualTo(token);
        assertThat(share.isShowAttachments()).isTrue();
        assertThat(share.isShowHistory()).isFalse();
        assertThat(events.stream(BoardChanged.class)).contains(new BoardChanged(board.getId()));
    }

    @Test
    void replacingTheLinkKeepsItsOptions() {
        shareWith(new ShareOptions(true, true, false));

        BoardShare renewed = shareService.createShare(board.getId(), ALICE);

        assertThat(renewed.isShowAttachments()).isTrue();
        assertThat(renewed.isShowNotes()).isTrue();
        assertThat(renewed.isShowHistory()).isFalse();
    }

    @Test
    void onlyTheOwnerChangesOptions() {
        shareService.createShare(board.getId(), ALICE);
        ShareOptions all = new ShareOptions(true, true, true);

        assertThatThrownBy(() -> shareService.updateOptions(board.getId(), BOB, all)).satisfies(BoardShareServiceTests::assertNotFound);
    }

    @Test
    void optionsOfAnUnsharedBoardAreNotFound() {
        assertThatThrownBy(() -> shareService.updateOptions(board.getId(), ALICE, new ShareOptions(true, null, null)))
                .satisfies(BoardShareServiceTests::assertNotFound);
    }
}
