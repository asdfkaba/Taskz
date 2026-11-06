package com.example.demo.task;

import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardDtos.MoveTaskRequest;
import com.example.demo.board.BoardService;
import com.example.demo.board.LabelService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private static final Logger LOG = LoggerFactory.getLogger(TaskController.class);
    public static final int MAX_DESCRIPTION_LENGTH = 10_000;
    static final int MAX_TITLE_LENGTH = 255; // the column's size

    private final TaskRepository repository;
    private final UserRepository userRepository;
    private final BoardService boardService;
    private final LabelService labelService;
    private final TaskHistoryService historyService;
    private final RuleEngine ruleEngine;

    public TaskController(TaskRepository repository, UserRepository userRepository, BoardService boardService,
                          LabelService labelService, TaskHistoryService historyService, RuleEngine ruleEngine) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.boardService = boardService;
        this.labelService = labelService;
        this.historyService = historyService;
        this.ruleEngine = ruleEngine;
    }

    @GetMapping("/{id}")
    public TaskDto getTaskById(@PathVariable("id") int id) {
        return TaskDto.from(findTask(id));
    }

    @PostMapping("")
    public ResponseEntity<TaskDto> createTask(@RequestBody TaskRequest input) {
        String email = currentUserEmail();
        LOG.info("Creating task for user '{}'", email);

        if (input.columnId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "columnId is required");
        }
        BoardColumn column = boardService.findColumn(input.columnId(), email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Task task = new Task();
        copyEditableFields(input, task);
        task.setUser(user);
        task.setBoardColumn(column);
        applyLabels(input, task);
        applyChecklist(input, task);
        task.setPosition(repository.countByBoardColumn(column));
        repository.save(task);
        historyService.recordCreated(task, email);
        ruleEngine.taskEntered(task, email);
        boardService.changed(column.getBoard());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(task.getId())
                .toUri();
        return ResponseEntity.created(location).body(TaskDto.from(task));
    }

    @PutMapping("/{id}")
    public TaskDto updateTask(@PathVariable("id") int id, @RequestBody TaskRequest input) {
        Task task = findTask(id);
        // Someone else saved the task since this edit started: refuse rather than overwrite.
        if (input.revision() != null && !input.revision().equals(TaskDto.revisionOf(task))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone else changed this task in the meantime");
        }
        TaskHistoryService.Snapshot before = TaskHistoryService.Snapshot.of(task);
        copyEditableFields(input, task);
        applyLabels(input, task);
        applyChecklist(input, task);
        Task saved = repository.save(task);
        historyService.recordUpdate(before, saved, currentUserEmail());
        boardService.changed(saved.getBoardColumn().getBoard());
        return TaskDto.from(saved);
    }

    /** Ticks or unticks one checklist item. Not a conflict for someone editing the task (see TaskDto.revisionOf). */
    @PatchMapping("/{id}/checklist/{itemId}")
    public TaskDto setChecked(@PathVariable("id") int id, @PathVariable("itemId") int itemId,
                              @RequestBody CheckedRequest input) {
        if (input.checked() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "checked is required");
        }
        Task task = findTask(id);
        ChecklistItem item = task.getChecklist().stream()
                .filter(i -> i.getId() == itemId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found"));
        if (item.isChecked() != input.checked()) {
            item.setChecked(input.checked());
            historyService.recordCheckbox(task, item.getText(), input.checked(), currentUserEmail());
        }
        Task saved = repository.save(task);
        boardService.changed(saved.getBoardColumn().getBoard());
        return TaskDto.from(saved);
    }

    public record CheckedRequest(Boolean checked) {
    }

    @PatchMapping("/{id}/move")
    public TaskDto moveTask(@PathVariable("id") int id, @RequestBody MoveTaskRequest input) {
        if (input.columnId() == null || input.position() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "columnId and position are required");
        }
        return TaskDto.from(boardService.moveTask(id, input.columnId(), input.position(), currentUserEmail()));
    }

    @DeleteMapping("/{id}/delete")
    public void deleteTask(@PathVariable("id") int id) {
        Task task = findTask(id);
        historyService.recordDeleted(task, currentUserEmail());
        repository.delete(task);
        boardService.changed(task.getBoardColumn().getBoard());
    }

    // Newest first. Only for the owner; shared boards don't show history.
    @GetMapping("/{id}/history")
    public List<TaskHistoryService.EventDto> getHistory(@PathVariable("id") int id) {
        return historyService.historyOf(findTask(id));
    }

    // Tasks of boards the user can't open are reported as "not found" so their ids don't leak.
    private Task findTask(int id) {
        return boardService.findTask(id, currentUserEmail());
    }

    // Only the editable fields are copied, so a client can't set id, owner, column or timestamps.
    private static void copyEditableFields(TaskRequest from, Task to) {
        String title = from.title() == null ? "" : from.title().strip();
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be empty");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        if (from.description() != null && from.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        to.setTitle(title);
        to.setDescription(from.description());
        to.setDue_date(from.due_date());
    }

    // Items with an id must be items of this task; they keep their tick. Blank items are dropped.
    private static void applyChecklist(TaskRequest from, Task to) {
        if (from.checklist() == null) {
            return;
        }
        Map<Integer, ChecklistItem> existing = new HashMap<>();
        to.getChecklist().forEach(item -> existing.put(item.getId(), item));
        List<ChecklistItem> items = new ArrayList<>();
        for (TaskRequest.ChecklistItemRequest request : from.checklist()) {
            String text = Checklists.clean(request == null ? null : request.text());
            if (text.isEmpty()) {
                continue;
            }
            ChecklistItem item = request.id() == null ? new ChecklistItem(to, text, false) : existing.remove(request.id());
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Checklist item " + request.id() + " is not an item of this task");
            }
            item.setText(text);
            items.add(item);
        }
        if (items.size() > Checklists.MAX_ITEMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A checklist can have at most " + Checklists.MAX_ITEMS + " items");
        }
        to.getChecklist().clear();
        to.getChecklist().addAll(items);
        Checklists.renumber(to);
    }

    // Only labels of the task's own board are accepted (checked in LabelService).
    private void applyLabels(TaskRequest from, Task to) {
        if (from.labelIds() != null) {
            to.setLabels(labelService.labelsForTask(from.labelIds(), to.getBoardColumn().getBoard()));
        }
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
