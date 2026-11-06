package com.example.demo.board;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Settings of a {@link BoardRule}, stored as JSON: items for APPEND_CHECKLIST and REPLACE_CHECKLIST,
 * text and days for ADD_NOTE_REMINDER; removeOnLeave (true or absent) deletes that note again when the
 * task leaves the column.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RulePayload(List<String> items, String text, Integer days, Boolean removeOnLeave) {

    public boolean removesOnLeave() {
        return Boolean.TRUE.equals(removeOnLeave);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static RulePayload parse(String json) {
        try {
            return JSON.readValue(json, RulePayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
