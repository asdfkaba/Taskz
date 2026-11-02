package com.example.demo.auth.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component("github")
public class GithubOAuth2Provider extends AbstractOAuthProvider {

    public GithubOAuth2Provider(
            @Value("${spring.security.oauth2.client.provider.github.token-endpoint}") String tokenEndpoint,
            @Value("${spring.security.oauth2.client.registration.github.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.github.client-secret}") String clientSecret,
            @Value("${spring.security.oauth2.client.registration.github.redirect-uri}") String redirectUri,
            RestTemplate restTemplate) {
        super(tokenEndpoint, clientId, clientSecret, redirectUri, restTemplate);
    }

    // The primary address, if GitHub verified it. The profile's public email isn't used: the list
    // is the only place that says whether an address is verified.
    @Override
    @SuppressWarnings("unchecked")
    protected Optional<String> verifiedEmail(Map<String, Object> tokenResponse) {
        List<Map<String, Object>> emails = getWithAccessToken(tokenResponse, "https://api.github.com/user/emails", List.class);
        if (emails == null) {
            return Optional.empty();
        }
        return emails.stream()
                .filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
                .findFirst()
                .flatMap(e -> nonBlank(e.get("email")));
    }
}
