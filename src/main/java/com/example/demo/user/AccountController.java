package com.example.demo.user;

import com.example.demo.user.AccountService.Export;
import com.example.demo.user.AccountService.SessionDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Account settings. Like /account/tokens, only with a login: the token filter keeps API tokens out.
@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteAccountRequest(String email) {
    }

    @GetMapping("/account/sessions")
    public List<SessionDto> sessions(@CookieValue(name = "refresh_token", required = false) String refreshToken) {
        return accountService.sessions(currentUserEmail(), refreshToken);
    }

    @DeleteMapping("/account/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endSession(@PathVariable("id") long id) {
        accountService.endSession(id, currentUserEmail());
    }

    // Logs out every other device.
    @DeleteMapping("/account/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endOtherSessions(@CookieValue(name = "refresh_token", required = false) String refreshToken) {
        accountService.endOtherSessions(currentUserEmail(), refreshToken);
    }

    @GetMapping("/account/export")
    public ResponseEntity<Export> export(@CookieValue(name = "refresh_token", required = false) String refreshToken) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"taskz-export.json\"")
                .body(accountService.export(currentUserEmail(), refreshToken));
    }

    @DeleteMapping("/account")
    public ResponseEntity<Void> delete(@RequestBody DeleteAccountRequest request) {
        accountService.delete(currentUserEmail(), request.email());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired("access_token"), expired("refresh_token"))
                .build();
    }

    private static String expired(String cookie) {
        return ResponseCookie.from(cookie, "").httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(0)
                .build().toString();
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
