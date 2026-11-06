package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.task.TaskNoteController;
import com.example.demo.task.TaskNoteService;
import com.example.demo.task.TaskNoteService.NoteDto;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskNoteController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
@WithMockUser(username = TaskNoteControllerTests.ALICE)
class TaskNoteControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskNoteService noteService;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    TokenService tokenService;

    private static NoteDto note(String text) {
        return new NoteDto(5, text, "Alice", 1, Instant.parse("2026-10-28T10:00:00Z"), null, null, true, true);
    }

    @Test
    void listReturnsTheNotes() throws Exception {
        when(noteService.notesOf(1, ALICE)).thenReturn(List.of(note("Called")));
        mockMvc.perform(get("/tasks/1/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("Called"))
                .andExpect(jsonPath("$[0].user").value("Alice"))
                // With a zone, so the browser can convert it to local time.
                .andExpect(jsonPath("$[0].created").value("2026-10-28T10:00:00Z"));
    }

    @Test
    void createReturns201() throws Exception {
        when(noteService.addNote(1, ALICE, "Called", null)).thenReturn(note("Called"));
        mockMvc.perform(post("/tasks/1/notes").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Called\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void patchAndDelete() throws Exception {
        when(noteService.updateNote(5, ALICE, "Changed", null)).thenReturn(note("Changed"));
        mockMvc.perform(patch("/notes/5").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Changed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Changed"));
        mockMvc.perform(delete("/notes/5")).andExpect(status().isNoContent());
        verify(noteService).deleteNote(5, ALICE);
    }

    @Test
    void theReminderIsWallClockTimeBothWays() throws Exception {
        LocalDateTime at = LocalDateTime.of(2026, 11, 20, 9, 30);
        when(noteService.addNote(1, ALICE, "Call back", at)).thenReturn(
                new NoteDto(6, "Call back", "Alice", 1, Instant.parse("2026-10-28T10:00:00Z"), null, at, true, true));
        mockMvc.perform(post("/tasks/1/notes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Call back\",\"remindAt\":\"2026-11-20T09:30:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remindAt").value("2026-11-20T09:30:00"));
    }

    @Test
    @WithAnonymousUser
    void notesNeedALogin() throws Exception {
        mockMvc.perform(get("/tasks/1/notes")).andExpect(status().isForbidden());
        verifyNoInteractions(noteService);
    }
}
