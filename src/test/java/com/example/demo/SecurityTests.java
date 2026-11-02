package com.example.demo;

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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
class SecurityTests {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TaskRepository taskRepository;

    @MockBean
    UserRepository userRepository;

    @MockBean
    TokenService tokenService;

    @MockBean
    BoardService boardService;

    @MockBean
    LabelService labelService;

    @MockBean
    TaskHistoryService historyService;

    @Test
    void ohneLoginKeinZugriff() throws Exception {
        mockMvc.perform(get("/tasks/1"))
                .andExpect(status().isForbidden());
    }
}
