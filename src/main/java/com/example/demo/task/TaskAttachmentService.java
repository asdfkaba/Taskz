package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.board.BoardService;
import com.example.demo.task.FileTypes.FileType;
import com.example.demo.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Files attached to tasks. Like notes, everyone who can open the board sees and changes them, and shared
 * boards don't include them. Only the types in {@link FileTypes} are accepted, checked by content.
 */
@Service
public class TaskAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(TaskAttachmentService.class);
    static final int MAX_NAME_LENGTH = 200;

    private final TaskAttachmentRepository attachmentRepository;
    private final BoardService boardService;
    private final UserRepository userRepository;
    private final TaskHistoryService historyService;
    private final AttachmentStorage storage;
    private final AttachmentProperties properties;

    public TaskAttachmentService(TaskAttachmentRepository attachmentRepository, BoardService boardService,
                                 UserRepository userRepository, TaskHistoryService historyService,
                                 AttachmentStorage storage, AttachmentProperties properties) {
        this.attachmentRepository = attachmentRepository;
        this.boardService = boardService;
        this.userRepository = userRepository;
        this.historyService = historyService;
        this.storage = storage;
        this.properties = properties;
    }

    public record AttachmentDto(Integer id, String name, String contentType, long size, boolean image,
                                String user, Instant created) {
        static AttachmentDto from(TaskAttachment attachment) {
            return new AttachmentDto(attachment.getId(), attachment.getFileName(), attachment.getContentType(),
                    attachment.getSize(), attachment.getContentType().startsWith("image/"),
                    attachment.getUser() == null ? null : attachment.getUser().getName(), attachment.getCreated());
        }
    }

    /** What the page shows and checks before an upload: the size limit and the accepted types. */
    public record UploadSettings(long maxFileSize, String types, List<String> extensions) {
    }

    public UploadSettings uploadSettings() {
        return new UploadSettings(properties.maxFileSize().toBytes(), FileTypes.ALLOWED, FileTypes.extensions());
    }

    /** The message for a file above the size limit. */
    static String tooLargeMessage(AttachmentProperties properties) {
        return "Files can be at most " + readable(properties.maxFileSize().toBytes());
    }

    /** A file to send: its attachment row and where it is on disk. */
    public record Download(TaskAttachment attachment, Path file) {
    }

    @Transactional(readOnly = true)
    public List<AttachmentDto> attachmentsOf(int taskId, String email) {
        return attachmentRepository.findByTaskOrderByCreatedDescIdDesc(findTask(taskId, email)).stream()
                .map(AttachmentDto::from)
                .toList();
    }

    @Transactional
    public AttachmentDto upload(int taskId, String email, MultipartFile file) {
        Task task = findTask(taskId, email);
        String name = validName(file.getOriginalFilename());
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
        }
        long maxFile = properties.maxFileSize().toBytes();
        if (file.getSize() > maxFile) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, tooLargeMessage(properties));
        }
        // Counted for the board's owner: the files are on their board and go when they delete it.
        Board board = task.getBoardColumn().getBoard();
        long maxTotal = properties.maxTotalPerUser().toBytes();
        if (attachmentRepository.totalSizeOfBoardsOf(board.getUser()) + file.getSize() > maxTotal) {
            String whose = boardService.isOwner(board, email) ? "Your attachments" : "The attachments of this board's owner";
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    whose + " would take more than " + readable(maxTotal) + "; delete some first");
        }

        Path upload = null;
        try {
            upload = storage.newUploadFile();
            file.transferTo(upload);
            FileType type = FileTypes.detect(upload, name).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only " + FileTypes.ALLOWED + " files can be attached, with an extension that fits the content"));
            String key = UUID.randomUUID().toString();
            storage.store(upload, key);
            storage.deleteOnRollback(key);
            TaskAttachment attachment = attachmentRepository.save(new TaskAttachment(task,
                    userRepository.findByEmail(email).orElse(null), name, type.contentType, file.getSize(), key));
            historyService.recordAttachment(task, name, true, email);
            return AttachmentDto.from(attachment);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the upload", e);
        } finally {
            deleteTemporary(upload);
        }
    }

    @Transactional(readOnly = true)
    public Download download(int attachmentId, String email) {
        TaskAttachment attachment = findAttachment(attachmentId, email);
        Path file = storage.file(attachment.getStorageKey());
        if (!Files.isRegularFile(file)) {
            log.warn("Attachment {} has no file {}", attachment.getId(), attachment.getStorageKey());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
        }
        return new Download(attachment, file);
    }

    @Transactional
    public void delete(int attachmentId, String email) {
        TaskAttachment attachment = findAttachment(attachmentId, email);
        attachmentRepository.delete(attachment);
        historyService.recordAttachment(attachment.getTask(), attachment.getFileName(), false, email);
        storage.deleteAfterCommit(attachment.getStorageKey());
    }

    /**
     * Files of deleted tasks and boards: the database drops their rows (ON DELETE CASCADE), this
     * removes the files. Hourly; files younger than an hour stay, their upload may still be running.
     */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    @Transactional(readOnly = true)
    public void removeOrphanFiles() {
        int removed = storage.removeOrphans(new HashSet<>(attachmentRepository.findAllStorageKeys()), Duration.ofHours(1));
        if (removed > 0) {
            log.info("Removed {} attachment files without an attachment", removed);
        }
    }

    // Only the last path segment (some browsers send one), without control characters.
    static String validName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file needs a name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File names can be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    // Whole megabytes where they fit, otherwise kilobytes, so the message never names a smaller limit.
    static String readable(long bytes) {
        long mb = 1024 * 1024;
        if (bytes % mb == 0) {
            return (bytes / mb) + " MB";
        }
        return (bytes + 1023) / 1024 + " KB";
    }

    private static void deleteTemporary(Path upload) {
        if (upload == null) {
            return;
        }
        try {
            Files.deleteIfExists(upload);
        } catch (IOException e) {
            log.warn("Could not delete the temporary upload {}", upload, e);
        }
    }

    private Task findTask(int taskId, String email) {
        return boardService.findTask(taskId, email);
    }

    private TaskAttachment findAttachment(int attachmentId, String email) {
        return attachmentRepository.findById(attachmentId)
                .filter(a -> boardService.canAccess(a.getTask().getBoardColumn().getBoard(), email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
    }
}
