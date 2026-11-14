package com.example.demo.board;

import com.example.demo.task.TaskDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public final class BoardDtos {
    private BoardDtos() {
    }

    // own: the user's own board; else they joined it, and ownerName and ownerId (for the icon) say
    // whose it is. The slug is the board's address for this user (see BoardService.summariesOf).
    public record BoardSummary(Integer id, String name, String slug, boolean own, String ownerName, Integer ownerId) {
    }

    // An entry of GET /due: a task by its due date, or a note of the user by its reminder (noteId and
    // noteText set, due_date is the reminder). id is the task's; boardSlug the slug in the user's board list.
    public record DueTask(Integer id, String title, LocalDateTime due_date, String boardName, String boardSlug,
                          String columnName, String columnColor, Integer noteId, String noteText) {
    }

    // Columns are sorted by position, tasks by column position and then by their own position.
    // hasArchived: the board has archived tasks, which tasks leaves out.
    public record BoardDetail(Integer id, String name, String slug, List<ColumnDto> columns, List<LabelDto> labels,
                              List<TaskDto> tasks, String taskTemplate, boolean hasArchived) {
    }

    // Body and response of PUT /boards/{id}/task-template; null or blank removes the template.
    public record TaskTemplate(String template) {
    }

    /**
     * What a share link shows. Kept separate from BoardDetail on purpose, so fields added
     * for the owner later don't become public by accident.
     * path is the link's current form (slug of the board's name + key), so outdated links can be corrected.
     */
    public record SharedBoard(String name, String path, List<ColumnDto> columns, List<LabelDto> labels,
                              List<TaskDto> tasks) {
    }

    // path goes after /share/ in the link, e.g. "bewerbungen-E9ciaFkEQxu0EPNI3p7EFA".
    public record ShareDto(String path, Instant created) {
        static ShareDto from(BoardShare share) {
            return new ShareDto(ShareLinks.path(share.getBoard().getSlug(), share.getToken()), share.getCreated());
        }
    }

    public record ColumnDto(Integer id, String name, String color, int position) {
        static ColumnDto from(BoardColumn column) {
            return new ColumnDto(column.getId(), column.getName(), column.getColor(), column.getPosition());
        }
    }

    public record LabelDto(Integer id, String name, String color) {
        static LabelDto from(BoardLabel label) {
            return new LabelDto(label.getId(), label.getName(), label.getColor());
        }
    }

    // Creating a label needs both fields; when updating, a missing field stays unchanged.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelRequest(String name, String color) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewBoardRequest(String name) {
    }

    // Body of PATCH /boards/{id}; the name is the only thing that can change for now.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RenameBoardRequest(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewColumnRequest(String name, String color) {
    }

    /**
     * Changes for PATCH /columns/{id}. Absent fields stay unchanged.
     * colorSet tells "color": null (remove the color) apart from a missing "color".
     */
    public record ColumnPatch(String name, boolean colorSet, String color, Integer position) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoveTaskRequest(Integer columnId, Integer position) {
    }

    // An automation of a board (see BoardRule.Trigger and RulePayload for which settings go with what).
    public record RuleDto(Integer id, Integer columnId, String trigger, String action, List<String> items, String text, Integer days,
                          boolean removeOnLeave, List<Integer> userIds, Integer targetColumnId, boolean enabled) {
        static RuleDto from(BoardRule rule) {
            RulePayload payload = RulePayload.parse(rule.getPayload());
            return new RuleDto(rule.getId(), rule.getColumn().getId(), rule.getTrigger(), rule.getAction(), payload.items(), payload.text(), payload.days(), payload.removesOnLeave(),
                    payload.userIds(), payload.targetColumnId(), rule.isEnabled());
        }
    }

    // columnId, trigger and action only count when adding a rule; absent enabled leaves it as it is
    // (new: on), absent trigger means ENTERED_COLUMN.
    public record RuleRequest(Integer columnId, String action, List<String> items, String text, Integer days,
                              Boolean removeOnLeave, List<Integer> userIds, Boolean enabled, String trigger,
                              Integer targetColumnId) {
    }
}
