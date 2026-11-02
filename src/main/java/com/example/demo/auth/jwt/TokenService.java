package com.example.demo.auth.jwt;

import com.example.demo.user.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Login tokens: a short-lived signed JWT (access token) and a random refresh token stored in the
 * database. Both travel in cookies; when the access token has expired, the cookie filter in
 * WebSecurityConfig makes a new one from the refresh token.
 */
@Service
public class TokenService {

    /** How long a login lasts, and so how long both cookies live. */
    public static final Duration REFRESH_TOKEN_VALIDITY = Duration.ofDays(7);
    private static final Duration ACCESS_TOKEN_VALIDITY = Duration.ofMinutes(1);

    private final JwtEncoder encoder;
    private final JwtDecoder jwtDecoder;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenService(JwtEncoder encoder, JwtDecoder jwtDecoder, RefreshTokenRepository refreshTokenRepository) {
        this.encoder = encoder;
        this.jwtDecoder = jwtDecoder;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_VALIDITY))
                .subject(user.getEmail())
                .claim("name", user.getName())
                .claim("email", user.getEmail())
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** A new refresh token for a login. Expired ones of all users are dropped on the way. */
    @Transactional
    public RefreshToken generateRefreshToken(User user) {
        Instant now = Instant.now();
        refreshTokenRepository.deleteExpired(now);

        String plain = UUID.randomUUID().toString(); // 122 random bits from a secure generator
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(hash(plain));
        refreshToken.setPlainToken(plain);
        refreshToken.setUser(user);
        refreshToken.setIssuedAt(now);
        refreshToken.setExpiresAt(now.plus(REFRESH_TOKEN_VALIDITY));
        return refreshTokenRepository.save(refreshToken);
    }

    /** Fails (JwtException) for a token that is invalid or expired. */
    public Authentication parseToken(String token) {
        Jwt jwt = jwtDecoder.decode(token);
        return new UsernamePasswordAuthenticationToken(jwt.getSubject(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** A new access token for a known, unexpired refresh token. */
    @Transactional(readOnly = true)
    public String generateAccessTokenFromRefresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByToken(hash(refreshToken))
                .filter(t -> !t.isExpired())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));
        return generateToken(stored.getUser());
    }

    /**
     * How a refresh token is stored, so a database dump can't log anyone in. A plain hash is enough:
     * the tokens are random, not guessable passwords. V8 hashes the same way in SQL.
     */
    public static String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM has SHA-256
        }
    }
}
