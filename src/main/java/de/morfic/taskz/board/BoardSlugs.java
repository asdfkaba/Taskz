package de.morfic.taskz.board;

import java.text.Normalizer;
import java.util.Locale;

/** Turns a board name into the readable part of its URL, e.g. "Mein Board" -> "mein-board". */
final class BoardSlugs {

    static final int MAX_LENGTH = 50;
    static final String FALLBACK = "board";

    private BoardSlugs() {
    }

    static String fromName(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss");
        // Other accents: "é" -> "e"
        slug = Normalizer.normalize(slug, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        slug = slug.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? FALLBACK : slug;
    }
}
