package com.example.demo.auth.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@Component("google")
public class GoogleOAuth2Provider extends AbstractOAuthProvider {

    private final ObjectMapper objectMapper;

    public GoogleOAuth2Provider(
            @Value("${spring.security.oauth2.client.provider.google.token-endpoint}") String tokenEndpoint,
            @Value("${spring.security.oauth2.client.registration.google.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret}") String clientSecret,
            @Value("${spring.security.oauth2.client.registration.google.redirect-uri}") String redirectUri,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        super(tokenEndpoint, clientId, clientSecret, redirectUri, restTemplate);
        this.objectMapper = objectMapper;
    }

    // The ID token came straight from Google's token endpoint over HTTPS, so its signature needn't
    // be checked (OpenID Connect Core 3.1.3.7); its claims say whether Google verified the email.
    @Override
    protected Optional<String> verifiedEmail(Map<String, Object> tokenResponse) {
        if (!(tokenResponse.get("id_token") instanceof String idToken)) {
            return Optional.empty();
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> claims = objectMapper.readValue(payload, new TypeReference<>() {
            });
            // A boolean, though older tokens had the string "true".
            Object verified = claims.get("email_verified");
            boolean isVerified = Boolean.TRUE.equals(verified) || "true".equals(verified);
            return isVerified ? nonBlank(claims.get("email")) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
