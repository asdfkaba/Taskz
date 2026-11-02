package com.example.demo.auth.provider;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The code exchange all providers share (OAuth 2 authorization code with PKCE). */
public abstract class AbstractOAuthProvider implements OAuthProvider {
    protected final String tokenEndpoint;
    protected final String clientId;
    protected final String clientSecret;
    protected final String redirectUri;
    protected final RestTemplate restTemplate;

    protected AbstractOAuthProvider(String tokenEndpoint, String clientId, String clientSecret, String redirectUri,
                                    RestTemplate restTemplate) {
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.restTemplate = restTemplate;
    }

    /** The verified email from the provider's token response, or empty. */
    protected abstract Optional<String> verifiedEmail(Map<String, Object> tokenResponse);

    @Override
    public Optional<String> verifiedEmail(String code, String codeVerifier) {
        // Sent as a form, so the values are URL-encoded. Never log this: it holds the client secret.
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> tokenResponse = restTemplate.postForObject(tokenEndpoint, new HttpEntity<>(form, headers), Map.class);
        // GitHub answers a bad code with 200 and {"error": ...}; then there is no token.
        if (tokenResponse == null || tokenResponse.containsKey("error")) {
            return Optional.empty();
        }
        return verifiedEmail(tokenResponse);
    }

    /** GET with the access token from the token response; null without one. */
    protected <T> T getWithAccessToken(Map<String, Object> tokenResponse, String url, Class<T> type) {
        if (!(tokenResponse.get("access_token") instanceof String accessToken)) {
            return null;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), type).getBody();
    }

    static Optional<String> nonBlank(Object email) {
        return email instanceof String s && !s.isBlank() ? Optional.of(s.strip()) : Optional.empty();
    }
}
