package com.example.demo.task;

import com.example.demo.board.BoardLabel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

public record TaskDto(
        Integer id,
        String title,
        String description,
        LocalDateTime due_date,
        Instant created,
        Instant edited,
        Integer columnId,
        int position,
        List<Integer> labelIds,
        // Number of attached files; null on shared boards, which don't include attachments.
        Integer attachments,
        // Changes whenever a field of the edit form changes (see revisionOf).
        String revision) {

    public static TaskDto from(Task task) {
        List<Integer> labelIds = task.getLabels().stream().map(BoardLabel::getId).sorted().toList();
        return new TaskDto(task.getId(), task.getTitle(), task.getDescription(), task.getDue_date(),
                task.getCreated(), task.getEdited(),
                task.getBoardColumn().getId(), task.getPosition(), labelIds,
                task.getAttachmentCount(), revisionOf(task));
    }

    public TaskDto withoutAttachments() {
        return new TaskDto(id, title, description, due_date, created, edited, columnId, position, labelIds, null, revision);
    }

    /**
     * A fingerprint of what the edit form changes: title, description, due date, labels. A client
     * sends back the one it started editing from; if it differs now, someone else saved in between
     * (see TaskController.updateTask). Moving the task doesn't change it, so it is no conflict.
     */
    public static String revisionOf(Task task) {
        String content = String.join("\u0000", String.valueOf(task.getTitle()), String.valueOf(task.getDescription()),
                String.valueOf(task.getDue_date()),
                task.getLabels().stream().map(BoardLabel::getId).sorted().toList().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM has SHA-256
        }
    }
}
