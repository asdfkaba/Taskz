package com.example.demo;

import com.example.demo.auth.controller.AuthController;
import com.example.demo.auth.dto.AuthResponse;
import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.RefreshTokenRepository;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.auth.service.AuthService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.user.CustomUserDetailsService;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The login cookies are SameSite=Strict, so forms posted from other sites don't carry them. */
@WebMvcTest(AuthController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
class AuthCookieTests {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AuthService authService;
    @MockBean
    RefreshTokenRepository refreshTokenRepository;
    @MockBean
    UserRepository userRepository;
    @MockBean
    CustomUserDetailsService userDetailsService;
    @MockBean
    TokenService tokenService;

    @Test
    void loginCookiesAreStrict() throws Exception {
        RefreshToken refresh = new RefreshToken();
        refresh.setToken("refresh-value");
        when(authService.authenticateWithGoogle(any())).thenReturn(new AuthResponse("access-value", refresh));

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
}
