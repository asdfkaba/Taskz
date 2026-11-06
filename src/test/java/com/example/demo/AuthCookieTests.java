package com.example.demo;

import com.example.demo.auth.controller.AuthController;
import com.example.demo.auth.dto.AuthResponse;
import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.RefreshTokenRepository;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.auth.service.AuthService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.user.UserRepository;
import com.example.demo.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The login cookies are SameSite=Strict, so forms posted from other sites don't carry them. */
@WebMvcTest(AuthController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
class AuthCookieTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthService authService;
    @MockitoBean
    RefreshTokenRepository refreshTokenRepository;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    TokenService tokenService;

    @Test
    void loginCookiesAreStrict() throws Exception {
        RefreshToken refresh = new RefreshToken();
        refresh.setPlainToken("refresh-value");
        when(authService.login(any())).thenReturn(new AuthResponse("access-value", refresh));

        List<String> cookies = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"code_verifier\":\"v\",\"provider\":\"google\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeaders("Set-Cookie");

        assertThat(cookies).hasSize(2);
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("access_token=access-value"));
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("refresh_token=refresh-value"));
        assertThat(cookies).allSatisfy(c -> assertThat(c).contains("SameSite=Strict", "HttpOnly", "Secure"));
    }

    @Test
    void aRefusedLoginSaysWhyAndSetsNoCookies() throws Exception {
        when(authService.login(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No confirmed email"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"code_verifier\":\"v\",\"provider\":\"discord\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("No confirmed email"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty());
    }

    // The page shows the name, e.g. as the own icon among who is on a board.
    @Test
    @WithMockUser(username = "alice@example.com")
    void statusTellsEmailNameAndId() throws Exception {
        User alice = new User("Alice", "alice@example.com");
        alice.setId(4);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        mockMvc.perform(get("/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.id").value(4));
    }
}
