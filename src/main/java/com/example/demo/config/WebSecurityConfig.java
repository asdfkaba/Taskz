package com.example.demo.config;

import com.example.demo.auth.jwt.TokenService;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    private final RsaKeyProperties rsaKeys;
    private final TokenService tokenService;

    public WebSecurityConfig(RsaKeyProperties rsaKeys, @Lazy TokenService tokenService) {
        this.rsaKeys = rsaKeys;
        this.tokenService = tokenService;
    }

    /**
     * Logs the request in from the access_token cookie. If that token is missing, invalid or
     * expired (it lives a minute), a valid refresh_token cookie gets a new one, sent back as cookie.
     * Without either, the request stays anonymous and protected paths answer 403.
     */
    public static class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

        private final TokenService tokenService;

        public JwtCookieAuthenticationFilter(TokenService tokenService) {
            this.tokenService = tokenService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                Optional<String> accessToken = cookie(request, "access_token");
                if (accessToken.isPresent()) {
                    try {
                        SecurityContextHolder.getContext().setAuthentication(tokenService.parseToken(accessToken.get()));
                    } catch (Exception expired) {
                        cookie(request, "refresh_token").ifPresent(refresh -> renew(refresh, response));
                    }
                }
            }
            filterChain.doFilter(request, response);
        }

        private void renew(String refreshToken, HttpServletResponse response) {
            try {
                String newAccessToken = tokenService.generateAccessTokenFromRefresh(refreshToken);
                ResponseCookie cookie = ResponseCookie.from("access_token", newAccessToken)
                        .httpOnly(true)
                        .secure(true)
                        .path("/")
                        .sameSite("Strict")
                        .maxAge(TokenService.REFRESH_TOKEN_VALIDITY)
                        .build();
                response.setHeader(HttpHeaders.SET_COOKIE, cookie.toString());
                Authentication authentication = tokenService.parseToken(newAccessToken);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception invalid) {
                // Unknown or expired refresh token: the request stays anonymous.
            }
        }

        private static Optional<String> cookie(HttpServletRequest request, String name) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return Optional.empty();
            }
            return Arrays.stream(cookies).filter(c -> name.equals(c.getName())).map(Cookie::getValue).findFirst();
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/login**", "/auth/**", "/error", "/health", "/").permitAll()
                        // Read-only share links work without login.
                        .requestMatchers(HttpMethod.GET, "/share/*", "/share/*/events").permitAll()
                        .anyRequest().authenticated()
                ).addFilterBefore(new JwtCookieAuthenticationFilter(tokenService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(rsaKeys.publicKey()).build();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        JWK jwk = new RSAKey.Builder(rsaKeys.publicKey()).privateKey(rsaKeys.privateKey()).build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwks);
    }
}
