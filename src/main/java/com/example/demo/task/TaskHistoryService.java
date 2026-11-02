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

    /** The fields whose changes are recorded. Labels by name, sorted. */
    public record Snapshot(String title, String description, LocalDateTime dueDate, List<String> labels) {
        public static Snapshot of(Task task) {
            List<String> labels = task.getLabels().stream().map(BoardLabel::getName).sorted().toList();
            return new Snapshot(task.getTitle(), task.getDescription(), task.getDue_date(), labels);
        }
    }

    public record EventDto(Integer id, String type, Instant at, String user, Map<String, Object> changes) {
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
        if (!changes.isEmpty()) {
            save(task, Type.UPDATED, changes, email);
        }
    }

    @Transactional
    public void recordMove(Task task, BoardColumn from, BoardColumn to, String email) {
        if (!Objects.equals(from.getId(), to.getId())) {
            save(task, Type.MOVED, Map.of("column", List.of(from.getName(), to.getName())), email);
        }
    }

    /**
     * A checkbox in the description was ticked or unticked; recorded by its text, not as a description change.
     * parents: items that changed with it (completed or reopened); subItems: how many sub-items changed with it.
     */
    @Transactional
    public void recordCheckbox(Task task, String item, boolean checked, List<String> parents, int subItems, String email) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("item", item);
        if (!parents.isEmpty()) {
            changes.put("parents", parents);
        }
        if (subItems > 0) {
            changes.put("subItems", subItems);
        }
        save(task, checked ? Type.CHECKED : Type.UNCHECKED, changes, email);
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
                        e.getUser() == null ? null : e.getUser().getName(), parse(e.getChanges())))
                .toList();
    }

    private void save(Task task, Type type, Map<String, Object> changes, String email) {
        eventRepository.save(new TaskEvent(task.getBoardColumn().getBoard(), task.getId(),
                userRepository.findByEmail(email).orElse(null), type, write(changes)));
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
