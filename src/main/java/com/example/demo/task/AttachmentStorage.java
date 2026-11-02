package com.example.demo.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The attachment files on disk: one flat directory, each file named by its storage key (a UUID).
 * Uploads are written to a temporary ".upload-" file first and only get their key once they passed
 * the checks.
 */
@Component
public class AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String UPLOAD_PREFIX = ".upload-";

    private final Path dir;

    public AttachmentStorage(AttachmentProperties properties) {
        this.dir = properties.dir().toAbsolutePath().normalize();
    }

    /** A new empty temporary file in the attachment directory, for an upload in progress. */
    Path newUploadFile() throws IOException {
        Files.createDirectories(dir);
        return Files.createTempFile(dir, UPLOAD_PREFIX, "");
    }

    /** Gives a checked upload its key; the temporary file is gone afterwards. */
    void store(Path upload, String key) throws IOException {
        Files.move(upload, file(key), StandardCopyOption.ATOMIC_MOVE);
    }

    Path file(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a storage key: " + key);
        }
        return dir.resolve(key);
    }

    /** Deletes the file once the current transaction committed, so a rollback keeps it. */
    void deleteAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(key);
            }
        });
    }

    /** Deletes the file if the current transaction rolls back, so no file without a row stays behind. */
    void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(key);
                }
            }
        });
    }

    void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(file(key));
        } catch (IOException e) {
            log.warn("Could not delete attachment file {}", key, e);
        }
    }

    /**
     * Deletes files that no attachment refers to any more (their task or board was deleted) and
     * leftovers of failed uploads. Files younger than {@code minAge} stay, as their upload may still
     * be running.
     *
     * @return how many files were deleted
     */
    int removeOrphans(Set<String> knownKeys, Duration minAge) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(minAge);
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                boolean ours = KEY.matcher(name).matches() || name.startsWith(UPLOAD_PREFIX);
                if (!ours || knownKeys.contains(name) || !Files.isRegularFile(file)
                        || Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
                    continue;
                }
                Files.deleteIfExists(file);
                removed++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return removed;
    }
}
