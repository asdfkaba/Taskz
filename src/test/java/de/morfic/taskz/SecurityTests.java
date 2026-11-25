package de.morfic.taskz;

import de.morfic.taskz.task.RuleEngine;
import de.morfic.taskz.auth.jwt.TokenService;
import de.morfic.taskz.board.BoardService;
import de.morfic.taskz.board.LabelService;
import de.morfic.taskz.config.WebSecurityConfig;
import de.morfic.taskz.task.TaskController;
import de.morfic.taskz.task.TaskHistoryService;
import de.morfic.taskz.task.TaskRepository;
import de.morfic.taskz.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
class SecurityTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RuleEngine ruleEngine;

    @MockitoBean
    TaskRepository taskRepository;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    TokenService tokenService;

    @MockitoBean
    BoardService boardService;

    @MockitoBean
    LabelService labelService;

    @MockitoBean
    TaskHistoryService historyService;

    @Test
    void ohneLoginKeinZugriff() throws Exception {
        mockMvc.perform(get("/tasks/1"))
                .andExpect(status().isForbidden());
    }

    // The open paths pass the security chain (their controllers aren't part of this slice, so 404).
    @Test
    void openPathsNeedNoLogin() throws Exception {
        for (String path : List.of("/health", "/auth/status", "/share/abc", "/share/abc/events", "/error",
                "/share/abc/attachments/k", "/share/abc/tasks/1/attachments", "/share/abc/tasks/1/notes", "/share/abc/tasks/1/history")) {
            mockMvc.perform(get(path)).andExpect(result ->
                    assertThat(result.getResponse().getStatus()).as(path).isNotEqualTo(403));
        }
        mockMvc.perform(options("/tasks/1")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
        mockMvc.perform(post("/share/abc")).andExpect(status().isForbidden());
    }
}
