package com.example.demo.auth;

import com.example.demo.auth.provider.DiscordOAuth2Provider;
import com.example.demo.auth.provider.GithubOAuth2Provider;
import com.example.demo.auth.provider.GoogleOAuth2Provider;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Accounts are matched by email, so a provider may only hand out an address it has confirmed.
 * The providers' HTTP answers are imitated with MockRestServiceServer.
 */
class OAuthProviderTests {

    static final String TOKEN_URL = "https://provider.example/token";

    RestTemplate restTemplate;
    MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private void tokenResponse(String json) {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void userInfo(String url, String json) {
        server.expect(requestTo(url))
                .andExpect(header("Authorization", "Bearer at"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    // ---- Google: claims of the ID token ----

    private GoogleOAuth2Provider google() {
        return new GoogleOAuth2Provider(TOKEN_URL, "id", "secret", "https://app/cb", restTemplate, new ObjectMapper());
    }

    private static String idToken(String claimsJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void googleGivesTheEmailOnlyWhenVerified() {
        tokenResponse("{\"id_token\":\"" + idToken("{\"email\":\"a@example.com\",\"email_verified\":true}") + "\"}");
        assertThat(google().verifiedEmail("c", "v")).contains("a@example.com");
        server.verify();

        server.reset();
        tokenResponse("{\"id_token\":\"" + idToken("{\"email\":\"a@example.com\",\"email_verified\":false}") + "\"}");
        assertThat(google().verifiedEmail("c", "v")).isEmpty();

        server.reset();
        tokenResponse("{\"id_token\":\"" + idToken("{\"email\":\"a@example.com\"}") + "\"}");
        assertThat(google().verifiedEmail("c", "v")).isEmpty();

        server.reset();
        tokenResponse("{\"id_token\":\"not-a-jwt\"}");
        assertThat(google().verifiedEmail("c", "v")).isEmpty();
    }

    @Test
    void theCodeExchangeIsAnEncodedForm() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("code=a%26b%3Dc")))
                .andExpect(content().string(containsString("code_verifier=v")))
                .andExpect(content().string(containsString("client_secret=secret")))
                .andExpect(content().string(containsString("grant_type=authorization_code")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(google().verifiedEmail("a&b=c", "v")).isEmpty();
        server.verify();
    }

    // ---- GitHub: the primary address, if verified ----

    private GithubOAuth2Provider github() {
        return new GithubOAuth2Provider(TOKEN_URL, "id", "secret", "https://app/cb", restTemplate);
    }

    @Test
    void githubGivesThePrimaryAddressOnlyWhenVerified() {
        tokenResponse("{\"access_token\":\"at\"}");
        userInfo("https://api.github.com/user/emails", """
                [{"email":"other@example.com","primary":false,"verified":true},
                 {"email":"main@example.com","primary":true,"verified":true}]""");
        assertThat(github().verifiedEmail("c", "v")).contains("main@example.com");
        server.verify();

        server.reset();
        tokenResponse("{\"access_token\":\"at\"}");
        userInfo("https://api.github.com/user/emails", """
                [{"email":"victim@example.com","primary":true,"verified":false},
                 {"email":"other@example.com","primary":false,"verified":true}]""");
        assertThat(github().verifiedEmail("c", "v")).isEmpty();
    }

    @Test
    void githubAnswersABadCodeWithAnErrorBody() {
        tokenResponse("{\"error\":\"bad_verification_code\"}");
        assertThat(github().verifiedEmail("c", "v")).isEmpty();
        server.verify();
    }

    // ---- Discord: "verified" flag of the user ----

    private DiscordOAuth2Provider discord() {
        return new DiscordOAuth2Provider(TOKEN_URL, "id", "secret", "https://app/cb", restTemplate);
    }

    @Test
    void discordGivesTheEmailOnlyWhenVerified() {
        tokenResponse("{\"access_token\":\"at\"}");
        userInfo("https://discord.com/api/users/@me", "{\"email\":\"a@example.com\",\"verified\":true}");
        assertThat(discord().verifiedEmail("c", "v")).contains("a@example.com");
        server.verify();

        server.reset();
        tokenResponse("{\"access_token\":\"at\"}");
        userInfo("https://discord.com/api/users/@me", "{\"email\":\"victim@example.com\",\"verified\":false}");
        assertThat(discord().verifiedEmail("c", "v")).isEmpty();

        server.reset();
        tokenResponse("{\"access_token\":\"at\"}");
        userInfo("https://discord.com/api/users/@me", "{\"email\":null,\"verified\":true}");
        assertThat(discord().verifiedEmail("c", "v")).isEmpty();
    }
}
