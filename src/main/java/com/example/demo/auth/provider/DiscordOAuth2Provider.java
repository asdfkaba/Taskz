package com.example.demo.auth.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

@Component("discord")
public class DiscordOAuth2Provider extends AbstractOAuthProvider {

    public DiscordOAuth2Provider(
            @Value("${spring.security.oauth2.client.provider.discord.token-uri}") String tokenEndpoint,
            @Value("${spring.security.oauth2.client.registration.discord.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.discord.client-secret}") String clientSecret,
            @Value("${spring.security.oauth2.client.registration.discord.redirect-uri}") String redirectUri,
            RestTemplate restTemplate) {
        super(tokenEndpoint, clientId, clientSecret, redirectUri, restTemplate);
    }

    // Discord sends the email even before it is confirmed; "verified" says whether it is.
    @Override
    @SuppressWarnings("unchecked")
    protected Optional<String> verifiedEmail(Map<String, Object> tokenResponse) {
        Map<String, Object> user = getWithAccessToken(tokenResponse, "https://discord.com/api/users/@me", Map.class);
        if (user == null || !Boolean.TRUE.equals(user.get("verified"))) {
            return Optional.empty();
        }
        return nonBlank(user.get("email"));
    }
}
