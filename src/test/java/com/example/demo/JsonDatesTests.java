package com.example.demo;

import com.example.demo.config.AppConfig;
import com.example.demo.task.TaskHistoryService.EventDto;
import com.example.demo.task.TaskDto;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dates as the app's ObjectMapper writes them (including AppConfig, as in production).
 * The browser parses them with new Date(): points in time need a zone, due dates must not have one.
 */
@JsonTest
@Import(AppConfig.class)
class JsonDatesTests {

    @Autowired
    ObjectMapper mapper;

    @Test
    void pointsInTimeAreIsoStringsInUtc() throws Exception {
        EventDto event = new EventDto(1, "moved", Instant.parse("2026-10-29T09:15:30Z"), "Alice", 1, Map.of());
        assertThat(mapper.writeValueAsString(event)).contains("\"at\":\"2026-10-29T09:15:30Z\"");
    }

    @Test
    void dueDatesAreIsoStringsWithoutZone() throws Exception {
        TaskDto task = new TaskDto(1, "Title", null, LocalDateTime.of(2026, 11, 2, 9, 30),
                Instant.parse("2026-10-01T09:00:00Z"), null, null, 10, 0, List.of(), List.of(), 0, "abc");
        String json = mapper.writeValueAsString(task);
        assertThat(json).contains("\"due_date\":\"2026-11-02T09:30:00\"");
        assertThat(json).contains("\"created\":\"2026-10-01T09:00:00Z\"");
    }

    @Test
    void dueDatesAreReadFromBothFormats() throws Exception {
        // Open browser tabs may still send the old array format once.
        record Due(LocalDateTime due_date) {}
        assertThat(mapper.readValue("{\"due_date\":\"2026-11-02T09:30:00\"}", Due.class).due_date())
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 30));
        assertThat(mapper.readValue("{\"due_date\":[2026,11,2,9,30]}", Due.class).due_date())
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 30));
    }
}
