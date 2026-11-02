package com.example.demo;

import com.example.demo.board.*;
import com.example.demo.task.*;
import com.example.demo.task.TaskAttachmentService.AttachmentDto;
import com.example.demo.task.TaskAttachmentService.Download;
import com.example.demo.task.TaskHistoryService.EventDto;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({TaskAttachmentService.class, AttachmentStorage.class, BoardService.class, TaskHistoryService.class})
class TaskAttachmentServiceTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @TempDir
    static Path dir;

    // Small limits, so the tests stay small: 1 KB per file, 2 KB per user.
    @DynamicPropertySource
    static void attachments(DynamicPropertyRegistry registry) {
        registry.add("attachments.dir", () -> dir.toString());
        registry.add("attachments.max-file-size", () -> "1KB");
        registry.add("attachments.max-total-per-user", () -> "2KB");
    }

    @Autowired
    TaskAttachmentService attachments;
    @Autowired
    BoardService boardService;
    @Autowired
    TaskHistoryService history;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    EntityManager em;

    Task task;
    Task bobsTask;

    @BeforeEach
    void setUp() {
        task = newTask(boardService.createDefaultBoard(userRepository.save(new User("Alice", ALICE))));
        bobsTask = newTask(boardService.createDefaultBoard(userRepository.save(new User("Bob", BOB))));
    }

    private Task newTask(Board board) {
        Task t = new Task();
        t.setTitle("Bewerbung");
        t.setUser(board.getUser());
        t.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        return taskRepository.save(t);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private static MockMultipartFile text(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    // Files in the directory, temporary uploads included.
    private static List<Path> filesOnDisk() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }

    @Test
    void anUploadIsStoredWithTheDetectedType() throws IOException {
        // The browser's type is ignored.
        AttachmentDto png = attachments.upload(task.getId(), ALICE, file("screen.png", PNG));
        AttachmentDto md = attachments.upload(task.getId(), ALICE, text("prep.md", "# Interview"));
        em.flush();
        em.clear();

        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.image()).isTrue();
        assertThat(png.size()).isEqualTo(PNG.length);
        assertThat(png.user()).isEqualTo("Alice");
        assertThat(md.contentType()).isEqualTo("text/markdown; charset=utf-8");
        assertThat(md.image()).isFalse();

        assertThat(attachments.attachmentsOf(task.getId(), ALICE)).extracting(AttachmentDto::name)
                .containsExactly("prep.md", "screen.png");
        Download download = attachments.download(png.id(), ALICE);
        assertThat(Files.readAllBytes(download.file())).isEqualTo(PNG);
        assertThat(download.file().getFileName().toString()).doesNotContain("screen");

        assertThat(TaskDto.from(taskRepository.findById(task.getId()).orElseThrow()).attachments()).isEqualTo(2);
        assertThat(TaskDto.from(taskRepository.findById(bobsTask.getId()).orElseThrow()).attachments()).isZero();
    }

    @Test
    void attachingAndRemovingIsInTheHistory() {
        AttachmentDto png = attachments.upload(task.getId(), ALICE, file("screen.png", PNG));
        attachments.delete(png.id(), ALICE);
        em.flush();
        em.clear();

        List<EventDto> events = history.historyOf(taskRepository.findById(task.getId()).orElseThrow());
        assertThat(events).extracting(EventDto::type).containsExactly("detached", "attached");
        assertThat(events.get(0).changes()).containsEntry("file", "screen.png");
        assertThat(attachments.attachmentsOf(task.getId(), ALICE)).isEmpty();
    }

    @Test
    void wrongTypesAreRefusedAndLeaveNoFile() throws IOException {
        for (MockMultipartFile refused : List.of(file("cv.pdf", PNG), file("setup.exe", new byte[]{'M', 'Z', 0}),
                text("logo.svg", "<svg/>"), file("notes.txt", new byte[]{'a', 0, 'b'}))) {
            assertThatThrownBy(() -> attachments.upload(task.getId(), ALICE, refused))
                    .satisfies(e -> assertStatus(e, HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        }
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void sizeLimits() {
        assertThatThrownBy(() -> attachments.upload(task.getId(), ALICE, text("big.txt", "x".repeat(1025))))
                .satisfies(e -> assertStatus(e, HttpStatus.PAYLOAD_TOO_LARGE))
                .hasMessageContaining("at most 1 KB");
        attachments.upload(task.getId(), ALICE, text("a.txt", "x".repeat(1000)));
        attachments.upload(task.getId(), ALICE, text("b.txt", "x".repeat(1000)));
        // 2000 of 2048 bytes used.
        assertThatThrownBy(() -> attachments.upload(task.getId(), ALICE, text("c.txt", "x".repeat(100))))
                .satisfies(e -> assertStatus(e, HttpStatus.PAYLOAD_TOO_LARGE));
        // Bob has his own limit.
        attachments.upload(bobsTask.getId(), BOB, text("c.txt", "x".repeat(100)));
    }

    @Test
    void emptyOrNamelessFilesAreRefused() {
        assertThatThrownBy(() -> attachments.upload(task.getId(), ALICE, text("empty.txt", "")))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> attachments.upload(task.getId(), ALICE, text("", "x")))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void otherUsersAttachmentsAreNotFound() {
        AttachmentDto bobs = attachments.upload(bobsTask.getId(), BOB, file("screen.png", PNG));

        assertThatThrownBy(() -> attachments.upload(bobsTask.getId(), ALICE, file("x.png", PNG)))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> attachments.attachmentsOf(bobsTask.getId(), ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> attachments.download(bobs.id(), ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> attachments.delete(bobs.id(), ALICE))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

}
