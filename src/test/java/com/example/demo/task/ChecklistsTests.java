package com.example.demo.task;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChecklistsTests {

    private static Task taskWith(String... ticked) {
        Task task = new Task();
        for (String text : ticked) {
            task.getChecklist().add(new ChecklistItem(task, text.replace("x:", ""), text.startsWith("x:")));
        }
        return task;
    }

    private static List<String> view(Task task) {
        return task.getChecklist().stream()
                .map(i -> (i.isChecked() ? "x:" : "") + i.getText() + "@" + i.getPosition())
                .toList();
    }

    @Test
    void cleanDropsMarkdownPrefixesAndRejectsLongOrMultiLineItems() {
        assertThat(Checklists.clean("- [ ] Folder")).isEqualTo("Folder");
        assertThat(Checklists.clean("* [x] CV")).isEqualTo("CV");
        assertThat(Checklists.clean("1. Cover letter")).isEqualTo("Cover letter");
        assertThat(Checklists.clean("[ ]Photo")).isEqualTo("Photo");
        assertThat(Checklists.clean("-5 degrees")).isEqualTo("-5 degrees");
        assertThat(Checklists.clean("- [ ] ")).isEmpty();
        assertThat(Checklists.clean(null)).isEmpty();
        assertThatThrownBy(() -> Checklists.clean("a\nb")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> Checklists.clean("x".repeat(201))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void appendAddsOnlyMissingItemsAtTheEnd() {
        Task task = taskWith("x:CV");
        Checklists.append(task, List.of("CV", "Prepare", "Prepare"));
        assertThat(view(task)).containsExactly("x:CV@0", "Prepare@1");
    }

    @Test
    void appendStopsAtTheLimit() {
        Task task = new Task();
        Checklists.append(task, java.util.stream.IntStream.range(0, 120).mapToObj(i -> "Item " + i).toList());
        assertThat(task.getChecklist()).hasSize(Checklists.MAX_ITEMS);
    }

    @Test
    void replaceKeepsTicksOfItemsInBothLists() {
        Task task = taskWith("Apply", "x:Research", "x:CV");
        Checklists.replace(task, List.of("Research", "Thank-you mail"));
        assertThat(view(task)).containsExactly("x:Research@0", "Thank-you mail@1");
    }
}
