package com.example.demo;

import com.example.demo.task.RuleEngine;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.BoardService;
import com.example.demo.board.LabelService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.task.TaskController;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
        for (String path : List.of("/health", "/auth/status", "/share/abc", "/share/abc/events", "/error")) {
            mockMvc.perform(get(path)).andExpect(result ->
                    assertThat(result.getResponse().getStatus()).as(path).isNotEqualTo(403));
        }
        mockMvc.perform(options("/tasks/1")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
        mockMvc.perform(post("/share/abc")).andExpect(status().isForbidden());
    }
}
