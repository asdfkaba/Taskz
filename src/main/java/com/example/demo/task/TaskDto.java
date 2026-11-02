package com.example.demo.task;

import com.example.demo.board.BoardLabel;

import java.time.Instant;
import java.time.LocalDateTime;
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
        Integer attachments) {

    public static TaskDto from(Task task) {
        return new TaskDto(task.getId(), task.getTitle(), task.getDescription(), task.getDue_date(),
                task.getCreated(), task.getEdited(),
                task.getBoardColumn().getId(), task.getPosition(),
                task.getLabels().stream().map(BoardLabel::getId).sorted().toList(),
                task.getAttachmentCount());
    }

    public TaskDto withoutAttachments() {
        return new TaskDto(id, title, description, due_date, created, edited, columnId, position, labelIds, null);
    }
}
