package com.example.demo.auth;

import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.RefreshTokenRepository;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The database only gets the hash of a refresh token; the cookie gets the token. */
class RefreshTokenHashTests {

    RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    JwtEncoder encoder = mock(JwtEncoder.class);
    TokenService tokens = new TokenService(encoder, mock(JwtDecoder.class), repository);

    @Test
    void storesTheHashAndFindsTheTokenByIt() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        User alice = new User("Alice", "alice@example.com");

        RefreshToken issued = tokens.generateRefreshToken(alice);

        assertThat(issued.getToken()).isEqualTo(TokenService.hash(issued.getPlainToken())).hasSize(64);
        assertThat(issued.getToken()).isNotEqualTo(issued.getPlainToken());

        Jwt jwt = Jwt.withTokenValue("access").header("alg", "RS256").subject("alice@example.com").build();
        when(encoder.encode(any())).thenReturn(jwt);
        when(repository.findByToken(issued.getToken())).thenReturn(Optional.of(issued));
        assertThat(tokens.generateAccessTokenFromRefresh(issued.getPlainToken())).isEqualTo("access");
    }

    @Test
    void knownHash() {
        assertThat(TokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
