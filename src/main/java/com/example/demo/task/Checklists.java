package com.example.demo.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown task list items ("- [ ] Cover letter") in descriptions.
 * The frontend ticks a checkbox by sending the whole description with the changed items
 * (the clicked one, plus sub-items and parents that follow it); {@link #toggles} checks that
 * nothing but checkboxes changed and lists them. {@link #append} and {@link #replace} change items, for automations.
 */
final class Checklists {

    // List marker (also inside block quotes), then "[ ]" or "[x]", then the item text.
    private static final Pattern ITEM = Pattern.compile("^((?:[ \\t]*>)*[ \\t]*(?:[-*+]|\\d{1,9}[.)])[ \\t]+\\[)([ xX])(].*)$");
    private static final int MAX_ITEM_LENGTH = 200;

    /** One ticked or unticked item; line is 0-based. */
    record Toggle(int line, String item, boolean checked) {
    }

    private Checklists() {
    }

    /**
     * The checkboxes that differ between the two texts, in line order. Empty if nothing changed
     * or anything other than a checkbox changed.
     */
    static List<Toggle> toggles(String before, String after) {
        if (before == null || after == null) {
            return List.of();
        }
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);
        if (oldLines.length != newLines.length) {
            return List.of();
        }
        List<Toggle> toggles = new ArrayList<>();
        for (int i = 0; i < oldLines.length; i++) {
            if (oldLines[i].equals(newLines[i])) {
                continue;
            }
            Optional<Toggle> toggle = toggle(i, oldLines[i], newLines[i]);
            if (toggle.isEmpty()) {
                return List.of();
            }
            toggles.add(toggle.get());
        }
        return toggles;
    }

    /**
     * The description with an unticked item for each of {@code items} that it doesn't have yet,
     * ticked or not, anywhere in the text. New items go at the end: right below a list item that ends
     * the text, else after a blank line.
     */
    static String append(String description, List<String> items) {
        String text = description == null ? "" : description.stripTrailing();
        Set<String> present = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                present.add(item.group(3).substring(1).trim());
            }
        }
        StringBuilder added = new StringBuilder();
        for (String item : items) {
            if (present.add(item)) {
                added.append("\n- [ ] ").append(item);
            }
        }
        if (added.isEmpty()) {
            return description;
        }
        if (text.isEmpty()) {
            return added.substring(1);
        }
        String lastLine = text.substring(text.lastIndexOf('\n') + 1);
        return text + (ITEM.matcher(lastLine).matches() ? "" : "\n") + added;
    }

    /**
     * The description with its checklist replaced by {@code items}: every checkbox line goes, with the
     * indented lines right below it (notes, sub-items), and the new items take the place of the first
     * one. Items that were ticked before stay ticked. Other text stays. Without a checklist like {@link #append}.
     */
    static String replace(String description, List<String> items) {
        String text = description == null ? "" : description.stripTrailing();
        Set<String> ticked = new HashSet<>();
        List<String> kept = new ArrayList<>();
        int at = -1;
        boolean inItem = false;
        for (String line : text.split("\n", -1)) {
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                if (!item.group(2).equals(" ")) {
                    ticked.add(item.group(3).substring(1).trim());
                }
                if (at < 0) {
                    at = kept.size();
                }
                inItem = true;
            } else if (!(inItem && !line.isBlank() && (line.startsWith(" ") || line.startsWith("\t")))) {
                inItem = false;
                kept.add(line);
            }
        }
        if (at < 0) {
            return append(description, items);
        }
        kept.addAll(at, items.stream().map(i -> "- [" + (ticked.contains(i) ? "x" : " ") + "] " + i).toList());
        String result = String.join("\n", kept).stripTrailing();
        return result.equals(text) ? description : result;
    }

    private static Optional<Toggle> toggle(int line, String before, String after) {
        Matcher was = ITEM.matcher(before);
        Matcher is = ITEM.matcher(after);
        if (!was.matches() || !is.matches()
                || !was.group(1).equals(is.group(1)) || !was.group(3).equals(is.group(3))) {
            return Optional.empty();
        }
        boolean checkedBefore = !was.group(2).equals(" ");
        boolean checkedAfter = !is.group(2).equals(" ");
        if (checkedBefore == checkedAfter) {
            return Optional.empty(); // only "x" -> "X"
        }
        return Optional.of(new Toggle(line, itemText(is.group(3)), checkedAfter));
    }

    private static String itemText(String rest) {
        String text = rest.substring(1).trim(); // without the "]"
        return text.length() > MAX_ITEM_LENGTH ? text.substring(0, MAX_ITEM_LENGTH) + "…" : text;
    }
}
