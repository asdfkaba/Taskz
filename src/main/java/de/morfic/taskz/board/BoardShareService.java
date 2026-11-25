package de.morfic.taskz.board;

import de.morfic.taskz.board.BoardDtos.ShareOptions;
import de.morfic.taskz.board.BoardDtos.SharedBoard;
import de.morfic.taskz.task.Task;
import de.morfic.taskz.task.TaskAttachmentService;
import de.morfic.taskz.task.TaskAttachmentService.AttachmentDto;
import de.morfic.taskz.task.TaskAttachmentService.Download;
import de.morfic.taskz.task.TaskDto;
import de.morfic.taskz.task.TaskHistoryService;
import de.morfic.taskz.task.TaskHistoryService.EventDto;
import de.morfic.taskz.task.TaskNoteService;
import de.morfic.taskz.task.TaskNoteService.NoteDto;
import de.morfic.taskz.task.TaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Read-only share links. Only the owner can see, create or revoke a board's link;
 * reading a board through its link needs no login. For now a board has at most one link.
 * The owner chooses whether the link also shows attachments, notes and history of the tasks, and
 * whether with or without the names of who uploaded, wrote or changed them.
 */
@Service
public class BoardShareService {

    private final BoardService boardService;
    private final BoardShareRepository shareRepository;
    private final ApplicationEventPublisher events;
    private final TaskRepository taskRepository;
    private final TaskAttachmentService attachmentService;
    private final TaskNoteService noteService;
    private final TaskHistoryService historyService;

    public BoardShareService(BoardService boardService, BoardShareRepository shareRepository,
                             ApplicationEventPublisher events, TaskRepository taskRepository,
                             TaskAttachmentService attachmentService, TaskNoteService noteService,
                             TaskHistoryService historyService) {
        this.boardService = boardService;
        this.shareRepository = shareRepository;
        this.events = events;
        this.taskRepository = taskRepository;
        this.attachmentService = attachmentService;
        this.noteService = noteService;
        this.historyService = historyService;
    }

    @Transactional(readOnly = true)
    public Optional<BoardShare> getShare(int boardId, String email) {
        return shareRepository.findByBoard(boardService.findOwnBoard(boardId, email));
    }

    /**
     * Creates the board's link. An existing link is replaced, so the old one stops working; the new
     * one shows the same as the old one.
     */
    @Transactional
    public BoardShare createShare(int boardId, String email) {
        Board board = boardService.findOwnBoard(boardId, email);
        BoardShare share = new BoardShare(board);
        shareRepository.findByBoard(board).ifPresent(old -> {
            share.setShowAttachments(old.isShowAttachments());
            share.setShowNotes(old.isShowNotes());
            share.setShowHistory(old.isShowHistory());
            share.setHideNames(old.isHideNames());
        });
        shareRepository.deleteByBoard(board);
        events.publishEvent(new BoardShareEnded(boardId));
        return shareRepository.save(share);
    }

    /** Changes what the link shows; it stays the same link. 404 if the board isn't shared. */
    @Transactional
    public BoardShare updateOptions(int boardId, String email, ShareOptions options) {
        Board board = boardService.findOwnBoard(boardId, email);
        BoardShare share = shareRepository.findByBoard(board)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board isn't shared"));
        if (options.showAttachments() != null) {
            share.setShowAttachments(options.showAttachments());
        }
        if (options.showNotes() != null) {
            share.setShowNotes(options.showNotes());
        }
        if (options.showHistory() != null) {
            share.setShowHistory(options.showHistory());
        }
        if (options.hideNames() != null) {
            share.setHideNames(options.hideNames());
        }
        boardService.changed(board); // open views of the link load what they may show now
        return share;
    }

    @Transactional
    public void revokeShare(int boardId, String email) {
        shareRepository.deleteByBoard(boardService.findOwnBoard(boardId, email));
        events.publishEvent(new BoardShareEnded(boardId));
    }

    /**
     * The board behind a link path (see {@link ShareLinks}). Unknown, revoked and malformed links
     * all answer 404, so nothing can be learned from the answer.
     */
    @Transactional(readOnly = true)
    public SharedBoard sharedBoard(String path) {
        BoardShare share = findShare(path);
        Board board = share.getBoard();
        List<TaskDto> tasks = boardService.tasksOf(board);
        if (!share.isShowAttachments()) {
            tasks = tasks.stream().map(TaskDto::withoutAttachments).toList();
        }
        return new SharedBoard(board.getName(), ShareLinks.path(board.getSlug(), share.getToken()),
                boardService.columnsOf(board), boardService.labelsOf(board), tasks,
                boardService.archivedCountOf(board),
                share.isShowAttachments(), share.isShowNotes(), share.isShowHistory());
    }

    @Transactional(readOnly = true)
    public List<AttachmentDto> sharedAttachments(String path, int taskId) {
        BoardShare share = findShare(path);
        List<AttachmentDto> attachments = attachmentService.attachmentsOf(sharedTask(share, taskId, BoardShare::isShowAttachments));
        return !share.isHideNames() ? attachments : attachments.stream()
                .map(a -> new AttachmentDto(a.id(), a.name(), a.contentType(), a.size(), a.image(), null, null, a.created()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Download sharedDownload(String path, String key) {
        BoardShare share = findShare(path);
        if (!share.isShowAttachments()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
        }
        return attachmentService.download(key, isShownOn(share));
    }

    @Transactional(readOnly = true)
    public List<NoteDto> sharedNotes(String path, int taskId) {
        BoardShare share = findShare(path);
        List<NoteDto> notes = noteService.sharedNotesOf(sharedTask(share, taskId, BoardShare::isShowNotes));
        return !share.isHideNames() ? notes : notes.stream()
                .map(n -> new NoteDto(n.id(), n.text(), null, null, n.created(), n.edited(), n.remindAt(), n.canEdit(), n.canDelete()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventDto> sharedHistory(String path, int taskId) {
        BoardShare share = findShare(path);
        List<EventDto> events = historyService.historyOf(sharedTask(share, taskId, BoardShare::isShowHistory));
        return !share.isHideNames() ? events : events.stream()
                .map(e -> new EventDto(e.id(), e.type(), e.at(), null, null, e.changes()))
                .toList();
    }

    // A task the link shows, if it shares what is asked for; 404 otherwise, like an unknown link.
    private Task sharedTask(BoardShare share, int taskId, Predicate<BoardShare> shares) {
        return taskRepository.findById(taskId)
                .filter(task -> shares.test(share) && isShownOn(share).test(task))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }

    // Tasks on the board; archived ones aren't shared.
    private static Predicate<Task> isShownOn(BoardShare share) {
        return task -> task.getArchivedAt() == null
                && task.getBoardColumn().getBoard().getId().equals(share.getBoard().getId());
    }

    /** The id of the board behind a link path; 404 like {@link #sharedBoard}. */
    @Transactional(readOnly = true)
    public int sharedBoardId(String path) {
        return findShare(path).getBoard().getId();
    }

    private BoardShare findShare(String path) {
        return ShareLinks.token(path)
                .flatMap(shareRepository::findByToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
    }
}
