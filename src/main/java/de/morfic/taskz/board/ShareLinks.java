package de.morfic.taskz.board;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The part of a share link after /share/: the board's slug and a key, e.g. "bewerbungen-E9ciaFkEQxu0EPNI3p7EFA".
 * The key is the share token (a random UUID) in URL-safe Base64, 22 instead of 36 characters.
 * Only the key matters; the slug is there for people and may be outdated after a rename.
 * Links from before this format (a plain UUID) keep working.
 */
public final class ShareLinks {

    static final int KEY_LENGTH = 22;
    private static final Pattern PLAIN_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ShareLinks() {
    }

    public static String path(String slug, UUID token) {
        return (slug == null || slug.isBlank() ? "board" : slug) + "-" + key(token);
    }

    static String key(UUID token) {
        ByteBuffer bytes = ByteBuffer.allocate(16)
                .putLong(token.getMostSignificantBits())
                .putLong(token.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** The token in a link path, or empty if the path can't contain one. */
    static Optional<UUID> token(String path) {
        if (path == null) {
            return Optional.empty();
        }
        if (PLAIN_UUID.matcher(path).matches()) {
            return Optional.of(UUID.fromString(path));
        }
        // The key is always the last 22 characters; it may itself contain "-" and "_".
        if (path.length() < KEY_LENGTH) {
            return Optional.empty();
        }
        if (path.length() > KEY_LENGTH && path.charAt(path.length() - KEY_LENGTH - 1) != '-') {
            return Optional.empty();
        }
        String key = path.substring(path.length() - KEY_LENGTH);
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(key));
            UUID token = new UUID(buffer.getLong(), buffer.getLong());
            // 22 characters hold 132 bits; the decoder ignores the last 4. Accept only the one exact spelling.
            return key.equals(key(token)) ? Optional.of(token) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
