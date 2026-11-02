package com.example.demo.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

// columnId is only read when creating a task. Use PATCH /tasks/{id}/move to change it.
// labelIds replaces the task's labels; null leaves them as they are.
// revision: the task's revision the edit started from (TaskDto); null skips the check.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRequest(
        String title,
        String description,
        LocalDateTime due_date,
        Integer columnId,
        List<Integer> labelIds,
        String revision) {
}
