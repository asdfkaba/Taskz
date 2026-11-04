package com.example.demo.task;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Rules for checklist items, shared by editing a task and by board rules (RuleEngine). */
public final class Checklists {

    public static final int MAX_ITEM_LENGTH = 200;
    public static final int MAX_ITEMS = 100;
    // "- [ ] ", "* ", "1. ", "[x] ": people type items as Markdown; the checklist has its own boxes.
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^(?:(?:[-*+]|\\d{1,9}[.)])\\s+)?(?:\\[[ xX]]\\s*)?");

    private Checklists() {
    }

    /** The item's text: trimmed, without a Markdown list prefix; empty if nothing is left. Rejects longer or multi-line text. */
    public static String clean(String item) {
        String text = item == null ? "" : MARKDOWN_PREFIX.matcher(item.strip()).replaceFirst("").strip();
        if (text.contains("\n") || text.contains("\r")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Checklist items must be one line each");
        }
        if (text.length() > MAX_ITEM_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Checklist items must be at most " + MAX_ITEM_LENGTH + " characters");
        }
        return text;
    }

    /** Adds an unticked item for each text the checklist doesn't have yet, up to {@link #MAX_ITEMS}. */
    static void append(Task task, List<String> texts) {
        Set<String> present = new LinkedHashSet<>();
        task.getChecklist().forEach(item -> present.add(item.getText()));
        for (String text : texts) {
            if (task.getChecklist().size() < MAX_ITEMS && present.add(text)) {
                task.getChecklist().add(new ChecklistItem(task, text, false));
            }
        }
        renumber(task);
    }

    /** Makes {@code texts} the checklist. Items whose text was there before keep their tick. */
    static void replace(Task task, List<String> texts) {
        Map<String, ChecklistItem> old = new HashMap<>();
        task.getChecklist().forEach(item -> old.putIfAbsent(item.getText(), item));
        List<ChecklistItem> items = new ArrayList<>();
        for (String text : new LinkedHashSet<>(texts)) {
            ChecklistItem kept = old.remove(text);
            items.add(kept != null ? kept : new ChecklistItem(task, text, false));
        }
        task.getChecklist().clear();
        task.getChecklist().addAll(items);
        renumber(task);
    }

    static void renumber(Task task) {
        List<ChecklistItem> items = task.getChecklist();
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPosition(i);
        }
    }
}
