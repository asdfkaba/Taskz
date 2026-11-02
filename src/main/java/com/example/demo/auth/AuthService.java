package com.example.demo.auth.service;

import com.example.demo.auth.dto.AuthRequest;
import com.example.demo.auth.dto.AuthResponse;
import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.auth.provider.OAuthProvider;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import com.example.demo.user.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Login with a provider's code: find or create the user by their verified email and issue tokens. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final TokenService tokenService;
    private final Map<String, OAuthProvider> oauthProviders;

    public AuthService(Map<String, OAuthProvider> oauthProviders, UserRepository userRepository,
                       UserService userService, TokenService tokenService) {
        this.oauthProviders = oauthProviders;
        this.userRepository = userRepository;
        this.userService = userService;
        this.tokenService = tokenService;
    }

    public AuthResponse login(AuthRequest request) {
        OAuthProvider provider = request.getProvider() == null ? null : oauthProviders.get(request.getProvider());
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown login provider");
        }
        String email;
        try {
            email = provider.verifiedEmail(request.getCode(), request.getCode_verifier())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "Your account there has no confirmed email address. Confirm one and try again."));
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The login provider refused the login. Try again.");
        }

        User user = findOrRegister(email);
        String jwt = tokenService.generateToken(user);
        RefreshToken refreshToken = tokenService.generateRefreshToken(user);
        return new AuthResponse(jwt, refreshToken);
    }

    // Two first logins at the same moment (two tabs) both try to register; the unique email lets
    // only one through, the other then finds that user.
    private User findOrRegister(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            try {
                return userService.register(email, email);
            } catch (DataIntegrityViolationException e) {
                return userRepository.findByEmail(email).orElseThrow(() -> e);
            }
        });
    }
}
