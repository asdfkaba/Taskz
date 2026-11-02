package com.example.demo.task;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The file types that can be attached, recognized by their content, not by the name the browser
 * sends: images and PDFs by their first bytes ("magic numbers"), text by being valid UTF-8 without
 * NUL bytes. The file name's extension has to fit the recognized type.
 */
public final class FileTypes {

    public enum FileType {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg", "jpeg"),
        GIF("image/gif", "gif"),
        WEBP("image/webp", "webp"),
        PDF("application/pdf", "pdf"),
        TEXT("text/plain; charset=utf-8", "txt"),
        MARKDOWN("text/markdown; charset=utf-8", "md"),
        CSV("text/csv; charset=utf-8", "csv");

        public final String contentType;
        final List<String> extensions;

        FileType(String contentType, String... extensions) {
            this.contentType = contentType;
            this.extensions = List.of(extensions);
        }
    }

    /** For error messages: the extensions that are accepted. */
    public static final String ALLOWED = "PNG, JPEG, GIF, WebP, PDF, TXT, MD, CSV";

    private static final List<FileType> TEXT_TYPES = List.of(FileType.TEXT, FileType.MARKDOWN, FileType.CSV);

    private FileTypes() {
    }

    /**
     * The type of the file, if it is an allowed one and the extension of {@code fileName} fits its
     * content. Empty for anything else, e.g. a PNG named "cv.pdf" or an executable named "notes.txt".
     */
    public static Optional<FileType> detect(Path file, String fileName) throws IOException {
        String extension = extension(fileName);
        byte[] head = head(file, 16);
        Optional<FileType> binary = binaryType(head);
        if (binary.isPresent()) {
            return binary.filter(type -> type.extensions.contains(extension));
        }
        Optional<FileType> text = TEXT_TYPES.stream().filter(type -> type.extensions.contains(extension)).findFirst();
        if (text.isPresent() && isUtf8Text(file)) {
            return text;
        }
        return Optional.empty();
    }

    private static Optional<FileType> binaryType(byte[] head) {
        if (startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(FileType.PNG);
        }
        if (startsWith(head, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(FileType.JPEG);
        }
        if (startsWith(head, 0, 'G', 'I', 'F', '8', '7', 'a') || startsWith(head, 0, 'G', 'I', 'F', '8', '9', 'a')) {
            return Optional.of(FileType.GIF);
        }
        if (startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(FileType.WEBP);
        }
        if (startsWith(head, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(FileType.PDF);
        }
        return Optional.empty();
    }

    // Valid UTF-8 (a BOM is fine) and no NUL characters, which text files don't have but most
    // binary formats do.
    private static boolean isUtf8Text(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStream in = Files.newInputStream(file); Reader reader = new InputStreamReader(in, decoder)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\0') {
                        return false;
                    }
                }
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] head(Path file, int length) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.copyOf(in.readNBytes(length), length);
        }
    }

    private static boolean startsWith(byte[] data, int offset, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            if (offset + i >= data.length || (data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
