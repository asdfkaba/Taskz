package com.example.demo.board;

import java.util.Arrays;
import java.util.Optional;

/** What an API token may do; ApiTokenFilter maps each endpoint a token may reach to one scope. */
public enum ApiScope {
    TASKS_READ("tasks:read"),
    TASKS_CREATE("tasks:create"),
    TASKS_UPDATE("tasks:update"),
    TASKS_MOVE("tasks:move"),
    CHECKLIST_CHECK("checklist:check"),
    ATTACHMENTS_READ("attachments:read"),
    ATTACHMENTS_WRITE("attachments:write"),
    NOTES_READ("notes:read"),
    NOTES_WRITE("notes:write");

    private final String value;

    ApiScope(String value) {
        this.value = value;
    }

    /** The name in the API and the database, e.g. "tasks:read". */
    public String value() {
        return value;
    }

    static Optional<ApiScope> of(String value) {
        return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
    }
}
