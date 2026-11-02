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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
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

    @MockBean
    TaskNoteService noteService;
    @MockBean
    UserRepository userRepository;
    @MockBean
    TokenService tokenService;

    private static NoteDto note(String text) {
        return new NoteDto(5, text, "Alice", Instant.parse("2026-10-28T10:00:00Z"), null);
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
        when(noteService.addNote(1, ALICE, "Called")).thenReturn(note("Called"));
        mockMvc.perform(post("/tasks/1/notes").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Called\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void patchAndDelete() throws Exception {
        when(noteService.updateNote(5, ALICE, "Changed")).thenReturn(note("Changed"));
        mockMvc.perform(patch("/notes/5").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Changed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Changed"));
        mockMvc.perform(delete("/notes/5")).andExpect(status().isNoContent());
        verify(noteService).deleteNote(5, ALICE);
    }

    @Test
    @WithAnonymousUser
    void notesNeedALogin() throws Exception {
        mockMvc.perform(get("/tasks/1/notes")).andExpect(status().isForbidden());
        verifyNoInteractions(noteService);
    }
}
