package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardLabel;
import com.example.demo.task.TaskEvent.Type;
import com.example.demo.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Writes and reads the history of tasks. Callers take a {@link Snapshot} before changing a task
 * and pass it to {@link #recordUpdate}; only fields that really changed are stored.
 * Reordering within a column is not recorded, moving to another column is.
 */
@Service
public class TaskHistoryService {

    // Plain values only (strings, numbers, lists), so a default mapper is enough.
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskEventRepository eventRepository;
    private final UserRepository userRepository;

    public TaskHistoryService(TaskEventRepository eventRepository, UserRepository userRepository) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
    }

    /** The fields whose changes are recorded. Labels by name, sorted; checklist items by text, in order. */
    public record Snapshot(String title, String description, LocalDateTime dueDate, List<String> labels,
                           List<String> checklist) {
        public static Snapshot of(Task task) {
            List<String> labels = task.getLabels().stream().map(BoardLabel::getName).sorted().toList();
            List<String> checklist = task.getChecklist().stream().map(ChecklistItem::getText).toList();
            return new Snapshot(task.getTitle(), task.getDescription(), task.getDue_date(), labels, checklist);
        }
    }

    // userId: for the user's icon on the page; null like user if they are gone.
    public record EventDto(Integer id, String type, Instant at, String user, Integer userId, Map<String, Object> changes) {
    }

    @Transactional
    public void recordCreated(Task task, String email) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("title", task.getTitle());
        changes.put("column", task.getBoardColumn().getName());
        save(task, Type.CREATED, changes, email);
    }

    @Transactional
    public void recordUpdate(Snapshot before, Task task, String email) {
        Map<String, Object> changes = changesSince(before, task);
        if (!changes.isEmpty()) {
            save(task, Type.UPDATED, changes, email);
        }
    }

    /** Like {@link #recordUpdate}, but made by a board rule: no user, marked with "automation". */
    @Transactional
    public void recordAutomation(Snapshot before, Task task) {
        Map<String, Object> changes = changesSince(before, task);
        if (!changes.isEmpty()) {
            changes.put("automation", true);
            save(task, Type.UPDATED, changes, null);
        }
    }

    private static Map<String, Object> changesSince(Snapshot before, Task task) {
        Snapshot after = Snapshot.of(task);
        Map<String, Object> changes = new LinkedHashMap<>();
        putIfChanged(changes, "title", before.title(), after.title());
        putIfChanged(changes, "description", before.description(), after.description());
        putIfChanged(changes, "due_date", iso(before.dueDate()), iso(after.dueDate()));
        List<String> added = new ArrayList<>(after.labels());
        added.removeAll(before.labels());
        List<String> removed = new ArrayList<>(before.labels());
        removed.removeAll(after.labels());
        if (!added.isEmpty()) {
            changes.put("labelsAdded", added);
        }
        if (!removed.isEmpty()) {
            changes.put("labelsRemoved", removed);
        }
        // Renaming an item shows as removed and added; reordering alone isn't recorded.
        List<String> itemsAdded = new ArrayList<>(after.checklist());
        itemsAdded.removeAll(before.checklist());
        List<String> itemsRemoved = new ArrayList<>(before.checklist());
        itemsRemoved.removeAll(after.checklist());
        if (!itemsAdded.isEmpty()) {
            changes.put("checklistAdded", itemsAdded);
        }
        if (!itemsRemoved.isEmpty()) {
            changes.put("checklistRemoved", itemsRemoved);
        }
        return changes;
    }

    @Transactional
    public void recordMove(Task task, BoardColumn from, BoardColumn to, String email) {
        if (!Objects.equals(from.getId(), to.getId())) {
            save(task, Type.MOVED, Map.of("column", List.of(from.getName(), to.getName())), email);
        }
    }

    /** A checklist item was ticked or unticked; recorded by its text. */
    @Transactional
    public void recordCheckbox(Task task, String item, boolean checked, String email) {
        save(task, checked ? Type.CHECKED : Type.UNCHECKED, Map.of("item", item), email);
    }

    /** A file was attached to the task (added) or removed from it; recorded by its name. */
    @Transactional
    public void recordAttachment(Task task, String fileName, boolean added, String email) {
        save(task, added ? Type.ATTACHED : Type.DETACHED, Map.of("file", fileName), email);
    }

    @Transactional
    public void recordDeleted(Task task, String email) {
        save(task, Type.DELETED, Map.of("title", task.getTitle()), email);
    }

    /** Deletes the history of all tasks of a board, before the board itself is deleted. */
    @Transactional
    public void forgetBoard(Board board) {
        eventRepository.deleteByBoard(board);
    }

    @Transactional(readOnly = true)
    public List<EventDto> historyOf(Task task) {
        return eventRepository.findByTaskIdOrderByAtDescIdDesc(task.getId()).stream()
                .map(e -> new EventDto(e.getId(), e.getType().toLowerCase(), e.getAt(),
                        e.getUser() == null ? null : e.getUser().getName(),
                        e.getUser() == null ? null : e.getUser().getId(), parse(e.getChanges())))
                .toList();
    }

    private void save(Task task, Type type, Map<String, Object> changes, String email) {
        eventRepository.save(new TaskEvent(task.getBoardColumn().getBoard(), task.getId(),
                email == null ? null : userRepository.findByEmail(email).orElse(null), type, write(changes)));
    }

    private static void putIfChanged(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, Arrays.asList(before, after)); // asList allows null
        }
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    private static String write(Map<String, Object> changes) {
        try {
            return JSON.writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> parse(String changes) {
        try {
            return JSON.readValue(changes, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
