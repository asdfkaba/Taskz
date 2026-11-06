package com.example.demo.task;

import com.example.demo.board.BoardService;
import com.example.demo.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Notes on tasks. Everyone who can open the board sees and adds notes; only the author edits a
 * note, and the author or the board's owner deletes it. Notes of boards the user can't open are
 * reported as "not found". Shared boards don't include notes.
 */
@Service
public class TaskNoteService {

    static final int MAX_LENGTH = 5_000;

    private final TaskNoteRepository noteRepository;
    private final BoardService boardService;
    private final UserRepository userRepository;

    public TaskNoteService(TaskNoteRepository noteRepository, BoardService boardService,
                           UserRepository userRepository) {
        this.noteRepository = noteRepository;
        this.boardService = boardService;
        this.userRepository = userRepository;
    }

    // canEdit and canDelete are for the user asking, so the page shows only what works.
    // userId: for the author's icon on the page.
    public record NoteDto(Integer id, String text, String user, Integer userId, Instant created, Instant edited,
                          LocalDateTime remindAt, boolean canEdit, boolean canDelete) {
    }

    private NoteDto toDto(TaskNote note, String email) {
        return new NoteDto(note.getId(), note.getText(), note.getUser() == null ? null : note.getUser().getName(),
                note.getUser() == null ? null : note.getUser().getId(), note.getCreated(), note.getEdited(),
                note.getRemindAt(), canEdit(note, email), canDelete(note, email));
    }

    // Notes from before boards had members, or of deleted users, have no author: the owner's then.
    private boolean canEdit(TaskNote note, String email) {
        return note.getUser() == null ? isBoardOwner(note, email) : email.equals(note.getUser().getEmail());
    }

    private boolean canDelete(TaskNote note, String email) {
        return canEdit(note, email) || isBoardOwner(note, email);
    }

    private boolean isBoardOwner(TaskNote note, String email) {
        return boardService.isOwner(note.getTask().getBoardColumn().getBoard(), email);
    }

    @Transactional(readOnly = true)
    public List<NoteDto> notesOf(int taskId, String email) {
        return noteRepository.findByTaskOrderByCreatedDescIdDesc(boardService.findTask(taskId, email)).stream()
                .map(note -> toDto(note, email))
                .toList();
    }

    @Transactional
    public NoteDto addNote(int taskId, String email, String text, LocalDateTime remindAt) {
        Task task = boardService.findTask(taskId, email);
        TaskNote note = new TaskNote(task, userRepository.findByEmail(email).orElse(null), validText(text));
        note.setRemindAt(remindAt);
        return toDto(noteRepository.save(note), email);
    }

    /** Sets text and reminder; a null remindAt removes the reminder (the "Done" in "What's due"). */
    @Transactional
    public NoteDto updateNote(int noteId, String email, String text, LocalDateTime remindAt) {
        TaskNote note = findNote(noteId, email);
        if (!canEdit(note, email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author can edit a note");
        }
        String valid = validText(text);
        if (!valid.equals(note.getText())) {
            note.changeText(valid);
        }
        note.setRemindAt(remindAt);
        return toDto(note, email);
    }

    @Transactional
    public void deleteNote(int noteId, String email) {
        TaskNote note = findNote(noteId, email);
        if (!canDelete(note, email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author or the board's owner can delete a note");
        }
        noteRepository.delete(note);
    }

    private TaskNote findNote(int noteId, String email) {
        return noteRepository.findById(noteId)
                .filter(note -> boardService.canAccess(note.getTask().getBoardColumn().getBoard(), email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found"));
    }

    // Trimmed at the ends only, so line breaks inside stay.
    public static String validText(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note must not be empty");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Note must be at most " + MAX_LENGTH + " characters");
        }
        return trimmed;
    }
}
