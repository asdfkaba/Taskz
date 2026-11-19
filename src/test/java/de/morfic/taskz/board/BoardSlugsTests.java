package de.morfic.taskz.board;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoardSlugsTests {

    @Test
    void lowerCaseWithDashes() {
        assertThat(BoardSlugs.fromName("Q3 2026 Plan")).isEqualTo("q3-2026-plan");
    }

    @Test
    void germanUmlautsAreSpelledOut() {
        assertThat(BoardSlugs.fromName("Ärger über Straße")).isEqualTo("aerger-ueber-strasse");
    }

    @Test
    void otherAccentsAreDropped() {
        assertThat(BoardSlugs.fromName("Café Déjà-vu!")).isEqualTo("cafe-deja-vu");
    }

    @Test
    void namesWithoutLettersGetAFallback() {
        assertThat(BoardSlugs.fromName("  *** ")).isEqualTo("board");
        assertThat(BoardSlugs.fromName("🚀")).isEqualTo("board");
    }

    @Test
    void longNamesAreCutWithoutTrailingDash() {
        String slug = BoardSlugs.fromName("a".repeat(49) + " bcd");

        assertThat(slug).isEqualTo("a".repeat(49));
        assertThat(slug.length()).isLessThanOrEqualTo(BoardSlugs.MAX_LENGTH);
    }
}
