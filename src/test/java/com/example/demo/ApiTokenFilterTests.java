package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.ApiScope;
import com.example.demo.board.ApiTokenAuthentication;
import com.example.demo.board.ApiTokenService;
import com.example.demo.board.BoardService;
import com.example.demo.board.LabelService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.task.RuleEngine;
import com.example.demo.task.TaskController;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Which requests an API token gets through the security chain. */
@WebMvcTest(TaskController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
class ApiTokenFilterTests {

    static final String READ = "taskz_read";
    static final String WRITE = "taskz_write";
    static final String FILES = "taskz_files";
    static final String NOTES = "taskz_notes";

    @Autowired MockMvc mockMvc;

    @MockitoBean ApiTokenService apiTokenService;
    @MockitoBean RuleEngine ruleEngine;
    @MockitoBean TaskRepository taskRepository;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;
    @MockitoBean BoardService boardService;
    @MockitoBean LabelService labelService;
    @MockitoBean TaskHistoryService historyService;

    @BeforeEach
    void setUp() {
        when(apiTokenService.authenticate(anyString())).thenReturn(Optional.empty());
        when(apiTokenService.authenticate(READ)).thenReturn(token(ApiScope.TASKS_READ));
        when(apiTokenService.authenticate(WRITE)).thenReturn(
                token(ApiScope.TASKS_CREATE, ApiScope.TASKS_UPDATE, ApiScope.TASKS_MOVE, ApiScope.CHECKLIST_CHECK));
        when(apiTokenService.authenticate(FILES)).thenReturn(token(ApiScope.ATTACHMENTS_READ, ApiScope.ATTACHMENTS_WRITE));
        when(apiTokenService.authenticate(NOTES)).thenReturn(token(ApiScope.NOTES_READ, ApiScope.NOTES_WRITE));
        // Requests that pass the chain arrive as the token's user, and the mocked lookups say 404.
        when(boardService.findTask(anyInt(), eq("alice@example.com"))).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(boardService.findColumn(anyInt(), eq("alice@example.com"))).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Optional<ApiTokenAuthentication> token(ApiScope... scopes) {
        return Optional.of(new ApiTokenAuthentication("alice@example.com", 1, Set.of(scopes), null));
    }

    private int statusOf(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void unknownTokensGet401() throws Exception {
        mockMvc.perform(get("/tasks/1").header(HttpHeaders.AUTHORIZATION, "Bearer taskz_wrong"))
                .andExpect(status().isUnauthorized());
    }

    // Allowed requests pass the chain and reach the controller (404 from the mocks or 400 for the body).
    @Test
    void readTokensOnlyRead() throws Exception {
        assertThat(statusOf(get("/tasks/1"), READ)).isEqualTo(404);
        assertThat(statusOf(post("/tasks"), READ)).isEqualTo(403);
        assertThat(statusOf(put("/tasks/1"), READ)).isEqualTo(403);
    }

    @Test
    void writeTokensOnlyCreateAndChangeTasks() throws Exception {
        assertThat(statusOf(post("/tasks"), WRITE)).isEqualTo(400); // no columnId in the body
        assertThat(statusOf(put("/tasks/1"), WRITE)).isEqualTo(404);
        assertThat(statusOf(patch("/tasks/1/move"), WRITE)).isNotIn(401, 403);
        assertThat(statusOf(get("/tasks/1"), WRITE)).isEqualTo(403);
        assertThat(statusOf(delete("/tasks/1/delete"), WRITE)).isEqualTo(403);
    }

    // Only the scopes' endpoints pass; their controllers aren't in this slice, so passing ends in 404.
    @Test
    void attachmentAndNoteScopesOpenTheirEndpoints() throws Exception {
        assertThat(statusOf(get("/tasks/1/attachments"), FILES)).isEqualTo(404);
        assertThat(statusOf(get("/attachments/1"), FILES)).isEqualTo(404);
        assertThat(statusOf(post("/tasks/1/attachments"), FILES)).isEqualTo(404);
        assertThat(statusOf(delete("/attachments/1"), FILES)).isEqualTo(403);
        assertThat(statusOf(get("/tasks/1"), FILES)).isEqualTo(403);

        assertThat(statusOf(get("/tasks/1/notes"), NOTES)).isEqualTo(404);
        assertThat(statusOf(post("/tasks/1/notes"), NOTES)).isEqualTo(404);
        assertThat(statusOf(patch("/notes/1"), NOTES)).isEqualTo(404);
        assertThat(statusOf(delete("/notes/1"), NOTES)).isEqualTo(403);

        for (String token : new String[]{READ, WRITE}) {
            assertThat(statusOf(get("/tasks/1/attachments"), token)).as(token).isEqualTo(403);
            assertThat(statusOf(post("/tasks/1/notes"), token)).as(token).isEqualTo(403);
        }
        assertThat(statusOf(get("/attachments/settings"), READ)).isEqualTo(404);
    }

    // ApiTokenController isn't part of this slice, so a request that passes ends in 404.
    @Test
    void everyTokenMayAskWhatItCanDo() throws Exception {
        assertThat(statusOf(get("/token"), READ)).isEqualTo(404);
        assertThat(statusOf(get("/token"), WRITE)).isEqualTo(404);
        assertThat(statusOf(get("/token"), "taskz_wrong")).isEqualTo(401);
    }

    @Test
    void tokensNeverReachTheRest() throws Exception {
        for (String token : new String[]{READ, WRITE, FILES, NOTES}) {
            assertThat(statusOf(get("/boards"), token)).as(token).isEqualTo(403);
            assertThat(statusOf(delete("/boards/1"), token)).as(token).isEqualTo(403);
            assertThat(statusOf(post("/account/tokens"), token)).as(token).isEqualTo(403);
            assertThat(statusOf(get("/account/tokens"), token)).as(token).isEqualTo(403);
            assertThat(statusOf(post("/boards/1/invites"), token)).as(token).isEqualTo(403);
            assertThat(statusOf(get("/tasks/1/history"), token)).as(token).isEqualTo(403);
            assertThat(statusOf(post("/boards/1/labels"), token)).as(token).isEqualTo(403);
        }
    }
}
