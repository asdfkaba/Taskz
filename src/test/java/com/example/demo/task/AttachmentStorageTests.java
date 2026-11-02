package com.example.demo.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentStorageTests {

    @TempDir
    Path dir;
    AttachmentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new AttachmentStorage(new AttachmentProperties(dir, DataSize.ofMegabytes(10), DataSize.ofMegabytes(500)));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private String storedFile() throws IOException {
        Path upload = storage.newUploadFile();
        Files.writeString(upload, "content");
        String key = UUID.randomUUID().toString();
        storage.store(upload, key);
        assertThat(upload).doesNotExist();
        return key;
    }

    private static void complete(int status) {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                sync.afterCommit();
            }
            sync.afterCompletion(status);
        }
    }

    @Test
    void storedFilesAreNamedByTheirKey() throws IOException {
        String key = storedFile();
        assertThat(storage.file(key)).hasContent("content").hasParent(dir);
    }

    @Test
    void onlyKeysNameFiles() {
        assertThatThrownBy(() -> storage.file("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.file("notes.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteAfterCommitWaitsForTheCommit() throws IOException {
        String key = storedFile();
        storage.deleteAfterCommit(key);
        assertThat(storage.file(key)).exists();
        complete(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(storage.file(key)).doesNotExist();
    }

    @Test
    void aRolledBackDeleteKeepsTheFile() throws IOException {
        String key = storedFile();
        storage.deleteAfterCommit(key);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(storage.file(key)).exists();
    }

    @Test
    void aRolledBackUploadLeavesNoFile() throws IOException {
        String committed = storedFile();
        storage.deleteOnRollback(committed);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(storage.file(committed)).exists();

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        String rolledBack = storedFile();
        storage.deleteOnRollback(rolledBack);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(storage.file(rolledBack)).doesNotExist();
    }

    @Test
    void orphansOlderThanTheGraceTimeAreRemoved() throws IOException {
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
        String known = storedFile();
        String orphan = storedFile();
        String freshOrphan = storedFile();
        Path leftoverUpload = storage.newUploadFile();
        Path foreign = Files.writeString(dir.resolve("README"), "not ours");
        for (Path file : new Path[]{storage.file(known), storage.file(orphan), leftoverUpload, foreign}) {
            Files.setLastModifiedTime(file, old);
        }

        int removed = storage.removeOrphans(Set.of(known), Duration.ofHours(1));

        assertThat(removed).isEqualTo(2);
        assertThat(storage.file(known)).exists();
        assertThat(storage.file(orphan)).doesNotExist();
        assertThat(leftoverUpload).doesNotExist();
        assertThat(storage.file(freshOrphan)).exists();
        assertThat(foreign).exists();
    }

    @Test
    void noDirectoryNothingToRemove() {
        AttachmentStorage empty = new AttachmentStorage(
                new AttachmentProperties(dir.resolve("missing"), DataSize.ofMegabytes(1), DataSize.ofMegabytes(1)));
        assertThat(empty.removeOrphans(Set.of(), Duration.ZERO)).isZero();
    }
}
