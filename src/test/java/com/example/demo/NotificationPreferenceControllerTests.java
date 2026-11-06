package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.notification.NotificationPreference.Delivery;
import com.example.demo.notification.NotificationPreferenceController;
import com.example.demo.notification.NotificationPreferenceService;
import com.example.demo.notification.NotificationPreferenceService.MailSettings;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationPreferenceController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
class NotificationPreferenceControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationPreferenceService service;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    TokenService tokenService;

    @Test
    @WithMockUser(username = ALICE)
    void readAndChangeTheMailSetting() throws Exception {
        when(service.mailSettings(ALICE)).thenReturn(new MailSettings(true, ALICE, Delivery.OFF));
        when(service.setMailDelivery(ALICE, "DAILY")).thenReturn(new MailSettings(true, ALICE, Delivery.DAILY));
        mockMvc.perform(get("/account/notifications/mail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.delivery").value("OFF"));
        mockMvc.perform(put("/account/notifications/mail").contentType(MediaType.APPLICATION_JSON).content("{\"delivery\":\"DAILY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").value("DAILY"));
    }

    @Test
    @WithAnonymousUser
    void unsubscribingNeedsNoLoginTheSettingsDo() throws Exception {
        mockMvc.perform(post("/mail/unsubscribe/abc")).andExpect(status().isNoContent());
        verify(service).unsubscribe("abc");
        mockMvc.perform(get("/account/notifications/mail")).andExpect(status().isForbidden());
        mockMvc.perform(put("/account/notifications/mail").contentType(MediaType.APPLICATION_JSON).content("{\"delivery\":\"OFF\"}"))
                .andExpect(status().isForbidden());
    }
}
