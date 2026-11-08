package com.example.demo.task;

import com.example.demo.task.TaskNoteService.NoteDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

// Notes need a login (or an API token with a notes scope); they are not part of shared boards.
@RestController
public class TaskNoteController {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteRequest(String text, LocalDateTime remindAt) {
    }

    private final TaskNoteService noteService;

    public TaskNoteController(TaskNoteService noteService) {
        this.noteService = noteService;
    }

    // Newest first.
    @GetMapping("/tasks/{id}/notes")
    public List<NoteDto> getNotes(@PathVariable("id") int taskId) {
        return noteService.notesOf(taskId, currentUserEmail());
    }

    @PostMapping("/tasks/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteDto addNote(@PathVariable("id") int taskId, @RequestBody NoteRequest request) {
        return noteService.addNote(taskId, currentUserEmail(), request.text(), request.remindAt());
    }

    @PatchMapping("/notes/{id}")
    public NoteDto updateNote(@PathVariable("id") int noteId, @RequestBody NoteRequest request) {
        return noteService.updateNote(noteId, currentUserEmail(), request.text(), request.remindAt());
    }

    @DeleteMapping("/notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable("id") int noteId) {
        noteService.deleteNote(noteId, currentUserEmail());
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
