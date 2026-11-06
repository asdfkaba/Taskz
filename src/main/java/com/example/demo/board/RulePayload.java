package com.example.demo.board;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Settings of a {@link BoardRule}, stored as JSON: items for APPEND_CHECKLIST and REPLACE_CHECKLIST,
 * text and days for ADD_NOTE_REMINDER; removeOnLeave (true or absent) deletes that note again when the
 * task leaves the column. NOTIFY: text and userIds (owner or members of the board).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RulePayload(List<String> items, String text, Integer days, Boolean removeOnLeave, List<Integer> userIds) {

    public boolean removesOnLeave() {
        return Boolean.TRUE.equals(removeOnLeave);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static RulePayload parse(String json) {
        return JSON.readValue(json, RulePayload.class);
    }

    String toJson() {
        return JSON.writeValueAsString(this);
    }
}
