package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.BoardService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.notification.NotificationController;
import com.example.demo.notification.NotificationService;
import com.example.demo.notification.NotificationService.NotificationDto;
import com.example.demo.notification.NotificationService.NotificationList;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
@WithMockUser(username = NotificationControllerTests.ALICE)
class NotificationControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationService service;
    @MockitoBean
    BoardService boardService;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    TokenService tokenService;

    @Test
    void listSeenReadOneReadAll() throws Exception {
        NotificationDto item = new NotificationDto(5, "Please review", "Jobs", "jobs", 7, "Acme",
                Instant.parse("2026-11-06T10:00:00Z"), false);
        when(service.notificationsOf(eq(ALICE), any())).thenReturn(new NotificationList(List.of(item), 1));
        mockMvc.perform(get("/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unseen").value(1))
                .andExpect(jsonPath("$.items[0].text").value("Please review"))
                .andExpect(jsonPath("$.items[0].created").value("2026-11-06T10:00:00Z"));
        mockMvc.perform(post("/notifications/seen")).andExpect(status().isNoContent());
        verify(service).markAllSeen(ALICE);
        mockMvc.perform(post("/notifications/5/read")).andExpect(status().isNoContent());
        verify(service).markRead(5, ALICE);
        mockMvc.perform(post("/notifications/read-all")).andExpect(status().isNoContent());
        verify(service).markAllRead(ALICE);
    }

    @Test
    @WithAnonymousUser
    void notificationsNeedALogin() throws Exception {
        mockMvc.perform(get("/notifications")).andExpect(status().isForbidden());
    }
}
