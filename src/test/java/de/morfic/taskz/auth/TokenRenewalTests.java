package de.morfic.taskz.auth;

import de.morfic.taskz.TestRsaKeys;
import de.morfic.taskz.auth.jwt.TokenService;
import de.morfic.taskz.board.BoardController;
import de.morfic.taskz.board.BoardEvents;
import de.morfic.taskz.board.BoardService;
import de.morfic.taskz.config.WebSecurityConfig;
import de.morfic.taskz.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The access token lives a minute; the cookie filter renews it from the refresh token. */
@WebMvcTest(BoardController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class, BoardEvents.class})
class TokenRenewalTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TokenService tokenService;
    @MockitoBean
    BoardService boardService;
    @MockitoBean
    UserRepository userRepository;

    @Test
    void anExpiredAccessTokenIsRenewedFromAValidRefreshToken() throws Exception {
        when(tokenService.parseToken("expired")).thenThrow(new JwtException("expired"));
        when(tokenService.generateAccessTokenFromRefresh("refresh")).thenReturn("fresh");
        when(tokenService.parseToken("fresh")).thenReturn(new UsernamePasswordAuthenticationToken("alice@example.com", null, List.of()));
        when(boardService.summariesOf("alice@example.com")).thenReturn(List.of());

        mockMvc.perform(get("/boards").cookie(new Cookie("access_token", "expired"), new Cookie("refresh_token", "refresh")))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("access_token=fresh"), containsString("SameSite=Strict"),
                        // 7 days, not 7 days in milliseconds.
                        containsString("Max-Age=604800;"))));
        verify(boardService).summariesOf("alice@example.com");
    }

    @Test
    void anUnknownRefreshTokenLeavesTheRequestAnonymous() throws Exception {
        when(tokenService.parseToken("expired")).thenThrow(new JwtException("expired"));
        when(tokenService.generateAccessTokenFromRefresh("stolen")).thenThrow(new IllegalArgumentException("unknown"));

        mockMvc.perform(get("/boards").cookie(new Cookie("access_token", "expired"), new Cookie("refresh_token", "stolen")))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(boardService);
    }
}
