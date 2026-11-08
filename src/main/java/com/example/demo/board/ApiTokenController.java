package com.example.demo.board;

import com.example.demo.board.ApiTokenService.NewToken;
import com.example.demo.board.ApiTokenService.TokenAccess;
import com.example.demo.board.ApiTokenService.TokenDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// The user's own API tokens, in the account settings. Managing them needs a login: the token filter
// lets API tokens reach only GET /token.
@RestController
public class ApiTokenController {

    private final ApiTokenService tokenService;

    public ApiTokenController(ApiTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    // scopes: ApiScope values; expiresInDays: null for a token that doesn't expire.
    public record NewTokenRequest(Integer boardId, String name, List<String> scopes, Integer expiresInDays) {
    }

    @GetMapping("/account/tokens")
    public List<TokenDto> list() {
        return tokenService.list(currentUserEmail());
    }

    @PostMapping("/account/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public NewToken create(@RequestBody NewTokenRequest request) {
        return tokenService.create(request.boardId(), currentUserEmail(), request.name(), request.scopes(), request.expiresInDays());
    }

    @DeleteMapping("/account/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable("id") int tokenId) {
        tokenService.revoke(tokenId, currentUserEmail());
    }

    // For scripts: the board this request's token works on, its rights, columns and labels.
    @GetMapping("/token")
    public TokenAccess access() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof ApiTokenAuthentication token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Only for requests with an API token");
        }
        return tokenService.access(token);
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
