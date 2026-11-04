package com.example.demo.task;

import com.example.demo.task.Checklists.Toggle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChecklistsTests {

    private static final String LIST = """
            Steps:
            - [ ] Cover letter
            - [x] CV
              * [ ] Nested item
            1. [ ] Numbered
            > - [ ] Quoted""";

    @Test
    void tickingOneItemIsFoundWithLineAndText() {
        String after = LIST.replace("- [ ] Cover letter", "- [x] Cover letter");
        assertThat(Checklists.toggles(LIST, after)).containsExactly(new Toggle(1, "Cover letter", true));
    }

    @Test
    void untickingAndOtherListStyles() {
        assertThat(Checklists.toggles(LIST, LIST.replace("- [x] CV", "- [ ] CV")))
                .containsExactly(new Toggle(2, "CV", false));
        assertThat(Checklists.toggles(LIST, LIST.replace("* [ ] Nested", "* [X] Nested")))
                .containsExactly(new Toggle(3, "Nested item", true));
        assertThat(Checklists.toggles(LIST, LIST.replace("1. [ ] Numbered", "1. [x] Numbered")))
                .containsExactly(new Toggle(4, "Numbered", true));
        assertThat(Checklists.toggles(LIST, LIST.replace("> - [ ] Quoted", "> - [x] Quoted")))
                .containsExactly(new Toggle(5, "Quoted", true));
    }

    @Test
    void severalItemsAreListedInLineOrder() {
        String after = LIST.replace("- [x] CV", "- [ ] CV").replace("* [ ] Nested", "* [x] Nested");
        assertThat(Checklists.toggles(LIST, after))
                .containsExactly(new Toggle(2, "CV", false), new Toggle(3, "Nested item", true));
    }

    @Test
    void anyOtherChangeIsRejected() {
        String ticked = LIST.replace("- [ ] Cover letter", "- [x] Cover letter");
        // Item text changed as well
        assertThat(Checklists.toggles(LIST, LIST.replace("- [ ] Cover letter", "- [x] Cover"))).isEmpty();
        // A checkbox plus another line
        assertThat(Checklists.toggles(LIST, ticked.replace("Steps:", "Steps"))).isEmpty();
        // Line added
        assertThat(Checklists.toggles(LIST, ticked + "\n")).isEmpty();
        // Nothing changed, or only the case of the x
        assertThat(Checklists.toggles(LIST, LIST)).isEmpty();
        assertThat(Checklists.toggles(LIST, LIST.replace("- [x] CV", "- [X] CV"))).isEmpty();
        // No description
        assertThat(Checklists.toggles(null, LIST)).isEmpty();
        assertThat(Checklists.toggles(LIST, null)).isEmpty();
    }

    @Test
    void textThatOnlyLooksLikeABoxIsNotAnItem() {
        assertThat(Checklists.toggles("Use [ ] for empty boxes", "Use [x] for empty boxes")).isEmpty();
    }

    @Test
    void longItemTextIsShortened() {
        String before = "- [ ] " + "a".repeat(300);
        assertThat(Checklists.toggles(before, before.replace("[ ]", "[x]")).get(0).item()).hasSize(201).endsWith("…");
    }

    @Test
    void appendAddsMissingItemsBelowAListThatEndsTheText() {
        assertThat(Checklists.append("Notes\n- [x] CV\n", java.util.List.of("CV", "Prepare", "Prepare")))
                .isEqualTo("Notes\n- [x] CV\n- [ ] Prepare");
    }

    @Test
    void appendStartsANewParagraphAfterText() {
        assertThat(Checklists.append("Call them back", java.util.List.of("Prepare")))
                .isEqualTo("Call them back\n\n- [ ] Prepare");
        assertThat(Checklists.append(null, java.util.List.of("Prepare"))).isEqualTo("- [ ] Prepare");
    }

    @Test
    void appendLeavesTheTextAloneWhenEveryItemIsThere() {
        String text = "> - [X] Prepare  \nmore";
        assertThat(Checklists.append(text, java.util.List.of("Prepare"))).isSameAs(text);
    }

    @Test
    void replaceSwapsTheChecklistInPlaceAndKeepsTicksAndOtherText() {
        String text = "Contact: Ann\n\n- [ ] Apply\n- [x] CV\n  needs a photo\n  - [ ] Photo\n\nLink: x.com\n";
        assertThat(Checklists.replace(text, java.util.List.of("CV", "Prepare")))
                .isEqualTo("Contact: Ann\n\n- [x] CV\n- [ ] Prepare\n\nLink: x.com");
    }

    @Test
    void replaceWithoutAChecklistAppendsAndSameListChangesNothing() {
        assertThat(Checklists.replace("Notes", java.util.List.of("Prepare"))).isEqualTo("Notes\n\n- [ ] Prepare");
        String text = "- [x] Prepare\n";
        assertThat(Checklists.replace(text, java.util.List.of("Prepare"))).isSameAs(text);
    }
}
