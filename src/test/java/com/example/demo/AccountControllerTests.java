package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.user.AccountController;
import com.example.demo.user.AccountService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
class AccountControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AccountService accountService;
    @MockitoBean
    TokenService tokenService;

    @Test
    @WithMockUser(ALICE)
    void deletingTheAccountRemovesTheLoginCookies() throws Exception {
        List<String> cookies = mockMvc.perform(delete("/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeaders("Set-Cookie");

        verify(accountService).delete(ALICE, ALICE);
        assertThat(cookies).hasSize(2)
                .anySatisfy(c -> assertThat(c).startsWith("access_token=;").contains("Max-Age=0"))
                .anySatisfy(c -> assertThat(c).startsWith("refresh_token=;").contains("Max-Age=0"));
    }

    @Test
    @WithMockUser(ALICE)
    void theSessionListKnowsTheCurrentLogin() throws Exception {
        mockMvc.perform(get("/account/sessions").cookie(new Cookie("refresh_token", "this-browser")))
                .andExpect(status().isOk());
        verify(accountService).sessions(ALICE, "this-browser");

        mockMvc.perform(delete("/account/sessions").cookie(new Cookie("refresh_token", "this-browser")))
                .andExpect(status().isNoContent());
        verify(accountService).endOtherSessions(ALICE, "this-browser");
    }

    @Test
    @WithMockUser(ALICE)
    void theExportIsADownload() throws Exception {
        mockMvc.perform(get("/account/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"taskz-export.json\""));
    }

    @Test
    void withoutLoginNothingHappens() throws Exception {
        mockMvc.perform(delete("/account").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/account/export")).andExpect(status().isForbidden());
        verifyNoInteractions(accountService);
    }
}
