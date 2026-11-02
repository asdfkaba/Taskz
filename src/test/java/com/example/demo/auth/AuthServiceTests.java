package com.example.demo.auth;

import com.example.demo.auth.dto.AuthRequest;
import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.auth.provider.OAuthProvider;
import com.example.demo.auth.service.AuthService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import com.example.demo.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTests {

    OAuthProvider discord = mock(OAuthProvider.class);
    UserRepository users = mock(UserRepository.class);
    UserService userService = mock(UserService.class);
    TokenService tokens = mock(TokenService.class);
    AuthService auth;

    @BeforeEach
    void setUp() {
        auth = new AuthService(Map.of("discord", discord), users, userService, tokens);
        when(tokens.generateToken(any())).thenReturn("jwt");
        when(tokens.generateRefreshToken(any())).thenReturn(new RefreshToken());
    }

    private static AuthRequest request(String provider) {
        AuthRequest request = new AuthRequest();
        request.setCode("code");
        request.setCode_verifier("verifier");
        request.setProvider(provider);
        return request;
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    @Test
    void aVerifiedEmailLogsIntoItsAccount() {
        User alice = new User("Alice", "alice@example.com");
        when(discord.verifiedEmail("code", "verifier")).thenReturn(Optional.of("alice@example.com"));
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        assertThat(auth.login(request("discord")).getToken()).isEqualTo("jwt");
        verify(tokens).generateToken(alice);
        verifyNoInteractions(userService);
    }

    @Test
    void aNewVerifiedEmailRegisters() {
        User bob = new User("bob@example.com", "bob@example.com");
        when(discord.verifiedEmail(any(), any())).thenReturn(Optional.of("bob@example.com"));
        when(users.findByEmail("bob@example.com")).thenReturn(Optional.empty());
        when(userService.register("bob@example.com", "bob@example.com")).thenReturn(bob);

        auth.login(request("discord"));
        verify(tokens).generateToken(bob);
    }

    @Test
    void withoutAVerifiedEmailNobodyIsLoggedIn() {
        when(discord.verifiedEmail(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.login(request("discord")))
                .satisfies(e -> assertStatus(e, HttpStatus.UNAUTHORIZED))
                .hasMessageContaining("no confirmed email");
        verifyNoInteractions(users, userService, tokens);
    }

    @Test
    void unknownProviderAndProviderErrors() {
        assertThatThrownBy(() -> auth.login(request("myspace"))).satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> auth.login(request(null))).satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));

        when(discord.verifiedEmail(any(), any())).thenThrow(new ResourceAccessException("timeout"));
        assertThatThrownBy(() -> auth.login(request("discord"))).satisfies(e -> assertStatus(e, HttpStatus.UNAUTHORIZED));
    }

    @Test
    void aParallelFirstLoginFindsTheUserTheOtherOneRegistered() {
        User carol = new User("carol@example.com", "carol@example.com");
        when(discord.verifiedEmail(any(), any())).thenReturn(Optional.of("carol@example.com"));
        when(users.findByEmail("carol@example.com")).thenReturn(Optional.empty(), Optional.of(carol));
        when(userService.register(any(), any())).thenThrow(new DataIntegrityViolationException("user_email_unique"));

        auth.login(request("discord"));
        verify(tokens).generateToken(carol);
    }
}
