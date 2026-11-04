package com.example.demo.board;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Settings of a {@link BoardRule}, stored as JSON: items for APPEND_CHECKLIST and REPLACE_CHECKLIST, days for SET_DUE_IN. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RulePayload(List<String> items, Integer days) {

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
