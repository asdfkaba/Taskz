package de.morfic.taskz.board;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShareLinksTests {

    @Test
    void pathIsSlugDashAndA22CharacterKey() {
        UUID token = UUID.fromString("13d72268-5904-431b-b410-f348df9ec414");
        String path = ShareLinks.path("bewerbungen", token);
        assertThat(path).isEqualTo("bewerbungen-E9ciaFkEQxu0EPNI357EFA");
        assertThat(ShareLinks.token(path)).contains(token);
    }

    @Test
    void roundTripsRandomTokensWithAnySlug() {
        for (int i = 0; i < 2000; i++) {
            UUID token = UUID.randomUUID();
            for (String slug : new String[]{"bewerbungen", "my-board-2", "a"}) {
                assertThat(ShareLinks.token(ShareLinks.path(slug, token))).contains(token);
            }
            // The key alone works too.
            assertThat(ShareLinks.token(ShareLinks.key(token))).contains(token);
        }
    }

    @Test
    void keysMayContainDashAndUnderscore() {
        // All bits set: Base64url turns them into "_"; 0xfb... gives "-".
        UUID underscores = new UUID(-1L, -1L);
        UUID dashes = new UUID(0xfbefbefbefbefbefL, 0xbefbefbefbefbefbL);
        assertThat(ShareLinks.key(underscores)).contains("_");
        assertThat(ShareLinks.key(dashes)).contains("-");
        assertThat(ShareLinks.token(ShareLinks.path("x", underscores))).contains(underscores);
        assertThat(ShareLinks.token(ShareLinks.path("x", dashes))).contains(dashes);
    }

    @Test
    void oldPlainTokensStillParse() {
        UUID token = UUID.randomUUID();
        assertThat(ShareLinks.token(token.toString())).contains(token);
        assertThat(ShareLinks.token(token.toString().toUpperCase())).contains(token);
    }

    @Test
    void missingSlugFallsBackToBoard() {
        UUID token = UUID.randomUUID();
        assertThat(ShareLinks.path(null, token)).startsWith("board-");
        assertThat(ShareLinks.path(" ", token)).startsWith("board-");
    }

    @Test
    void malformedPathsGiveNothing() {
        assertThat(ShareLinks.token(null)).isEmpty();
        assertThat(ShareLinks.token("")).isEmpty();
        assertThat(ShareLinks.token("too-short")).isEmpty();
        assertThat(ShareLinks.token("not-a-uuid")).isEmpty();
        assertThat(ShareLinks.token("slug" + "E9ciaFkEQxu0EPNI357EFA")).isEmpty(); // no dash before the key
        // Same 128 bits, but the unused last bits are set: only the exact spelling counts.
        assertThat(ShareLinks.token("slug-E9ciaFkEQxu0EPNI357EFB")).isEmpty();
        assertThat(ShareLinks.token("slug-E9ciaFkEQxu0EPNI3p7E!A")).isEmpty(); // not Base64url
    }
}
