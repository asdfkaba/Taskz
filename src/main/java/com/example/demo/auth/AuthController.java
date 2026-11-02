package com.example.demo.auth.controller;

import com.example.demo.auth.dto.AuthRequest;
import com.example.demo.auth.dto.AuthResponse;
import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.RefreshTokenRepository;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.auth.service.AuthService;
import com.example.demo.user.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenRepository refreshTokenRepository;


    public AuthController(AuthService authService, RefreshTokenRepository refreshTokenRepository) {
        this.authService = authService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @GetMapping("/status")
    public ResponseEntity<UserInfoDto> checkLoginStatus(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // If the principal is the email, you can fetch the user:
        String email = authentication.getName();

        return ResponseEntity.ok(new UserInfoDto(email));
    }

    public static record UserInfoDto(String email) {}


    @PostMapping("/login")
    public ResponseEntity<?> authenticate(@RequestBody AuthRequest authRequest,
                                          HttpServletRequest request) {
        AuthResponse tokens;

        tokens = authService.login(authRequest);
        RefreshToken refreshToken = tokens.getRefreshToken();
        refreshToken.setUserAgent(request.getHeader("User-Agent"));
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null) {
            ip = request.getRemoteAddr(); // fallback
        } else {
            // X-Forwarded-For can contain multiple IPs (comma-separated), take the first
            ip = ip.split(",")[0].trim();
        }
        refreshToken.setIpAddress(ip);
        refreshTokenRepository.save(refreshToken);

        // Strict: browsers don't send the cookies with requests started by other sites (forms
        // posted from elsewhere), so those can't act as the user. The app only calls its own API.
        ResponseCookie accessCookie = ResponseCookie.from("access_token", tokens.getToken())
                .httpOnly(true)
                .secure(true) // enable if HTTPS
                .sameSite("Strict")
                .path("/")
                .maxAge(TokenService.REFRESH_TOKEN_VALIDITY)
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", tokens.getRefreshToken().getToken())
                .httpOnly(true)
                .secure(true) // enable if HTTPS
                .sameSite("Strict")
                .path("/")
                .maxAge(TokenService.REFRESH_TOKEN_VALIDITY)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body("Login successful");
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @Transactional
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // Get current user
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            // Delete refresh tokens from DB
            String refreshTokenValue = getCookieValue(request, "refresh_token");
            if (refreshTokenValue != null) {
                refreshTokenRepository.deleteByToken(refreshTokenValue); // or use find + delete if needed
            }

        }

        // Expire access_token cookie
        ResponseCookie expiredCookie = ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict") // or "Lax" depending on your setup
                .path("/")
                .maxAge(0)
                .build();

        response.setHeader(HttpHeaders.SET_COOKIE, expiredCookie.toString());

        return ResponseEntity.noContent().build();
    }

    // The reason goes into the body, so the login page can say why a login failed.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : "Login failed";
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", reason));
    }
}
