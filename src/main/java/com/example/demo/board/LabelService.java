package com.example.demo.board;

import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Labels of a board and which of them a task carries. Looked up by the owner's email like boards. */
@Service
public class LabelService {

    static final int MAX_NAME_LENGTH = 30;

    private final BoardService boardService;
    private final BoardLabelRepository labelRepository;
    private final TaskRepository taskRepository;
    private final TaskHistoryService historyService;

    public LabelService(BoardService boardService, BoardLabelRepository labelRepository, TaskRepository taskRepository,
                        TaskHistoryService historyService) {
        this.boardService = boardService;
        this.labelRepository = labelRepository;
        this.taskRepository = taskRepository;
        this.historyService = historyService;
    }

    @Transactional
    public BoardLabel addLabel(int boardId, String email, String name, String color) {
        Board board = boardService.findBoard(boardId, email);
        boardService.changed(board);
        return labelRepository.save(new BoardLabel(validName(name), validColor(color), board));
    }

    /** Absent (null) fields stay unchanged. */
    @Transactional
    public BoardLabel updateLabel(int labelId, String email, String name, String color) {
        BoardLabel label = findLabel(labelId, email);
        if (name != null) {
            label.setName(validName(name));
        }
        if (color != null) {
            label.setColor(validColor(color));
        }
        boardService.changed(label.getBoard());
        return label;
    }

    /** Deletes the label and takes it off every task that carries it. */
    @Transactional
    public void deleteLabel(int labelId, String email) {
        BoardLabel label = findLabel(labelId, email);
        for (Task task : taskRepository.findByLabelsContaining(label)) {
            TaskHistoryService.Snapshot before = TaskHistoryService.Snapshot.of(task);
            task.getLabels().remove(label);
            historyService.recordUpdate(before, task, email);
        }
        labelRepository.delete(label);
        boardService.changed(label.getBoard());
    }

    /**
     * The labels with the given ids, which must all belong to {@code board}.
     * Unknown ids and labels of other boards are rejected, so a task can't reference them.
     */
    @Transactional(readOnly = true)
    public Set<BoardLabel> labelsForTask(List<Integer> ids, Board board) {
        if (ids.isEmpty()) {
            return new HashSet<>();
        }
        Map<Integer, BoardLabel> ofBoard = labelRepository.findByBoardOrderById(board).stream()
                .collect(Collectors.toMap(BoardLabel::getId, Function.identity()));
        Set<BoardLabel> result = new HashSet<>();
        for (Integer id : ids) {
            BoardLabel label = id == null ? null : ofBoard.get(id);
            if (label == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label " + id + " is not a label of this board");
            }
            result.add(label);
        }
        return result;
    }

    private BoardLabel findLabel(int labelId, String email) {
        return labelRepository.findById(labelId)
                .filter(label -> boardService.canAccess(label.getBoard(), email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found"));
    }

    private static String validName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label name must not be empty");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Label name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    // Labels always have a color; stored in lower case like column colors.
    private static String validColor(String color) {
        if (color == null || !BoardService.HEX_COLOR.matcher(color).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Color must look like #rrggbb");
        }
        return color.toLowerCase();
    }
}
