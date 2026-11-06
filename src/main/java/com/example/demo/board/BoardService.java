package com.example.demo.board;

import com.example.demo.board.BoardDtos.BoardDetail;
import com.example.demo.board.BoardDtos.BoardSummary;
import com.example.demo.board.BoardDtos.ColumnDto;
import com.example.demo.board.BoardDtos.ColumnPatch;
import com.example.demo.board.BoardDtos.DueTask;
import com.example.demo.board.BoardDtos.LabelDto;
import com.example.demo.notification.NotificationRepository;
import com.example.demo.task.Task;
import com.example.demo.task.TaskController;
import com.example.demo.task.TaskDto;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskNote;
import com.example.demo.task.TaskNoteRepository;
import com.example.demo.task.TaskRepository;
import com.example.demo.task.RuleEngine;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Boards, their columns and the order of tasks within them.
 * Who may see and change a board is decided here, in {@link #canAccess}: its owner and its members
 * (see {@link BoardMemberService}). Everything is looked up through that, so boards of others are
 * reported as "not found". Deleting, sharing and managing members is for the owner only
 * ({@link #findOwnBoard}).
 */
@Service
public class BoardService {

    private record DefaultColumn(String name, String color) {
    }

    static final String DEFAULT_BOARD_NAME = "My board";
    private static final List<DefaultColumn> DEFAULT_COLUMNS = List.of(
            new DefaultColumn("To do", "#64748b"),
            new DefaultColumn("In progress", "#3b82f6"),
            new DefaultColumn("Done", "#22c55e"));
    static final int MAX_NAME_LENGTH = 50;
    // The template becomes a description, so it has the same limit.
    static final int MAX_TASK_TEMPLATE_LENGTH = TaskController.MAX_DESCRIPTION_LENGTH;
    static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final BoardRepository boardRepository;
    private final BoardColumnRepository columnRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final BoardShareRepository shareRepository;
    private final BoardLabelRepository labelRepository;
    private final TaskHistoryService historyService;
    private final BoardMemberRepository memberRepository;
    private final BoardRuleRepository ruleRepository;
    private final RuleEngine ruleEngine;
    private final TaskNoteRepository noteRepository;
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher events;

    public BoardService(BoardRepository boardRepository, BoardColumnRepository columnRepository,
                        TaskRepository taskRepository, UserRepository userRepository,
                        BoardShareRepository shareRepository, BoardLabelRepository labelRepository,
                        TaskHistoryService historyService, BoardMemberRepository memberRepository,
                        BoardRuleRepository ruleRepository, RuleEngine ruleEngine,
                        TaskNoteRepository noteRepository, NotificationRepository notificationRepository,
                        ApplicationEventPublisher events) {
        this.boardRepository = boardRepository;
        this.columnRepository = columnRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.shareRepository = shareRepository;
        this.labelRepository = labelRepository;
        this.historyService = historyService;
        this.memberRepository = memberRepository;
        this.ruleRepository = ruleRepository;
        this.ruleEngine = ruleEngine;
        this.noteRepository = noteRepository;
        this.notificationRepository = notificationRepository;
        this.events = events;
    }

    // The board every new user starts with.
    @Transactional
    public Board createDefaultBoard(User user) {
        return createBoardWithDefaultColumns(user, DEFAULT_BOARD_NAME);
    }

    @Transactional
    public Board createBoard(String email, String name) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return createBoardWithDefaultColumns(user, validName(name, "Board"));
    }

    /**
     * Deletes the board with all its members, columns, rules, tasks and labels (invites go with it in the
     * database). Only its owner can; a user may delete their last board.
     */
    @Transactional
    public void deleteBoard(int boardId, String email) {
        Board board = findOwnBoard(boardId, email);
        shareRepository.deleteByBoard(board);
        events.publishEvent(new BoardShareEnded(boardId));
        events.publishEvent(new BoardAccessEnded(boardId, null));
        historyService.forgetBoard(board);
        memberRepository.deleteByBoard(board);
        notificationRepository.deleteByBoard(board);
        // Deleting the tasks also clears their rows in task_label.
        taskRepository.deleteAll(taskRepository.findByBoardInOrder(board));
        labelRepository.deleteAll(labelRepository.findByBoardOrderById(board));
        ruleRepository.deleteByColumnBoard(board);
        columnRepository.deleteAll(columnRepository.findByBoardOrderByPosition(board));
        boardRepository.delete(board);
    }

    /**
     * Renames the board. The slug follows the new name, so the board's URL matches it again;
     * share links keep working because only their key counts.
     */
    @Transactional
    public Board renameBoard(int boardId, String email, String name) {
        Board board = findBoard(boardId, email);
        String newName = validName(name, "Board");
        if (!slugFits(board.getSlug(), newName)) {
            board.setSlug(uniqueSlug(board.getUser(), newName));
        }
        board.setName(newName);
        changed(board);
        return board;
    }

    // True if the slug already belongs to the name ("my-board" or "my-board-2" for "My board"),
    // so a rename that only changes case or punctuation keeps the URL.
    private static boolean slugFits(String slug, String name) {
        if (slug == null) {
            return false;
        }
        String base = BoardSlugs.fromName(name);
        return slug.equals(base) || slug.matches(Pattern.quote(base) + "-\\d+");
    }

    /** Adds slugs to boards that don't have one yet; returns how many were filled. */
    @Transactional
    public int fillMissingSlugs() {
        List<Board> boards = boardRepository.findBySlugIsNullOrderById();
        for (Board board : boards) {
            board.setSlug(uniqueSlug(board.getUser(), board.getName()));
        }
        return boards.size();
    }

    // "mein-board", or "mein-board-2", "-3", ... if the user already has a board with that slug.
    private String uniqueSlug(User user, String name) {
        String base = BoardSlugs.fromName(name);
        String slug = base;
        for (int n = 2; boardRepository.existsByUserAndSlug(user, slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private Board createBoardWithDefaultColumns(User user, String name) {
        Board board = new Board(name, user);
        board.setSlug(uniqueSlug(user, name));
        boardRepository.save(board);
        for (int i = 0; i < DEFAULT_COLUMNS.size(); i++) {
            DefaultColumn template = DEFAULT_COLUMNS.get(i);
            columnRepository.save(new BoardColumn(template.name(), template.color(), i, board));
        }
        return board;
    }

    /** The user's own boards. */
    @Transactional(readOnly = true)
    public List<Board> boardsOf(String email) {
        return boardRepository.findByUserEmailOrderById(email);
    }

    /**
     * The boards the user can open: their own, then those they joined. A joined board whose slug
     * one of the user's boards already has gets "-2", "-3", ... for this user, so each board has its
     * own address. Not stored; it can change when the user later creates a board with that name.
     */
    @Transactional(readOnly = true)
    public List<BoardSummary> summariesOf(String email) {
        List<BoardSummary> summaries = new ArrayList<>();
        Set<String> slugs = new HashSet<>();
        for (Board board : boardsOf(email)) {
            slugs.add(board.getSlug());
            summaries.add(new BoardSummary(board.getId(), board.getName(), board.getSlug(), true, null, null));
        }
        for (Board board : memberRepository.boardsOf(email)) {
            String slug = board.getSlug();
            for (int n = 2; slugs.contains(slug); n++) {
                slug = board.getSlug() + "-" + n;
            }
            slugs.add(slug);
            summaries.add(new BoardSummary(board.getId(), board.getName(), slug, false,
                    board.getUser().getName(), board.getUser().getId()));
        }
        return summaries;
    }

    /** Tasks with a due date and the user's note reminders on all boards they can open, soonest first, without done ones. */
    @Transactional(readOnly = true)
    public List<DueTask> dueTasksOf(String email) {
        Map<Integer, BoardSummary> summaries = new HashMap<>();
        for (BoardSummary summary : summariesOf(email)) {
            summaries.put(summary.id(), summary);
        }
        if (summaries.isEmpty()) {
            return List.of();
        }
        List<Board> boards = boardRepository.findAllById(summaries.keySet());
        List<DueTask> due = new ArrayList<>();
        for (Task task : taskRepository.findDue(boards)) {
            due.add(dueEntry(task, summaries, null, null));
        }
        for (TaskNote note : noteRepository.findReminders(email, boards)) {
            due.add(dueEntry(note.getTask(), summaries, note, note.getRemindAt()));
        }
        // Stable: on the same date, tasks stay before notes.
        due.sort(Comparator.comparing(DueTask::due_date));
        return due;
    }

    private static DueTask dueEntry(Task task, Map<Integer, BoardSummary> summaries, TaskNote note, LocalDateTime remindAt) {
        BoardColumn column = task.getBoardColumn();
        BoardSummary board = summaries.get(column.getBoard().getId());
        return new DueTask(task.getId(), task.getTitle(), note == null ? task.getDue_date() : remindAt,
                board.name(), board.slug(), column.getName(), column.getColor(),
                note == null ? null : note.getId(), note == null ? null : note.getText());
    }

    /** One board as {@link #summariesOf} lists it for the user. */
    @Transactional(readOnly = true)
    public BoardSummary summaryOf(int boardId, String email) {
        return summariesOf(email).stream()
                .filter(summary -> summary.id() == boardId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board not found"));
    }

    @Transactional(readOnly = true)
    public BoardDetail getBoard(int boardId, String email) {
        Board board = findBoard(boardId, email);
        return new BoardDetail(board.getId(), board.getName(), board.getSlug(), columnsOf(board), labelsOf(board),
                tasksOf(board), board.getTaskTemplate());
    }

    /** Sets the description new tasks of the board start with. Blank removes it. Returns what is stored. */
    @Transactional
    public String setTaskTemplate(int boardId, String email, String template) {
        Board board = findBoard(boardId, email);
        String value = template == null || template.isBlank() ? null : template.strip();
        if (value != null && value.length() > MAX_TASK_TEMPLATE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "template must be at most " + MAX_TASK_TEMPLATE_LENGTH + " characters");
        }
        board.setTaskTemplate(value);
        return value;
    }

    List<ColumnDto> columnsOf(Board board) {
        return columnRepository.findByBoardOrderByPosition(board).stream()
                .map(ColumnDto::from)
                .toList();
    }

    List<LabelDto> labelsOf(Board board) {
        return labelRepository.findByBoardOrderById(board).stream().map(LabelDto::from).toList();
    }

    // Sorted by column position, then by position within the column.
    List<TaskDto> tasksOf(Board board) {
        return taskRepository.findByBoardInOrder(board).stream()
                .map(TaskDto::from)
                .toList();
    }

    @Transactional
    public BoardColumn addColumn(int boardId, String email, String name, String color) {
        Board board = findBoard(boardId, email);
        int position = columnRepository.findByBoardOrderByPosition(board).size();
        changed(board);
        return columnRepository.save(new BoardColumn(validName(name, "Column"), validColor(color), position, board));
    }

    /**
     * Applies the given changes. A new position moves the column there and shifts the columns
     * in between; a position beyond the end puts the column last.
     */
    @Transactional
    public BoardColumn updateColumn(int columnId, String email, ColumnPatch patch) {
        BoardColumn column = findColumn(columnId, email);
        if (patch.name() != null) {
            column.setName(validName(patch.name(), "Column"));
        }
        if (patch.colorSet()) {
            column.setColor(validColor(patch.color()));
        }
        if (patch.position() != null) {
            List<BoardColumn> columns = new ArrayList<>(columnRepository.findByBoardOrderByPosition(column.getBoard()));
            columns.removeIf(c -> c.getId().equals(column.getId()));
            columns.add(Math.max(0, Math.min(patch.position(), columns.size())), column);
            for (int i = 0; i < columns.size(); i++) {
                columns.get(i).setPosition(i);
            }
        }
        changed(column.getBoard());
        return column;
    }

    /**
     * Deletes a column. A column that still has tasks needs an explicit choice:
     * move them to another column of the same board ({@code moveTo}) or delete them ({@code deleteTasks}).
     */
    @Transactional
    public void deleteColumn(int columnId, String email, Integer moveTo, boolean deleteTasks) {
        BoardColumn column = findColumn(columnId, email);
        if (moveTo != null && deleteTasks) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use either moveTo or deleteTasks, not both");
        }

        List<BoardColumn> columns = new ArrayList<>(columnRepository.findByBoardOrderByPosition(column.getBoard()));
        if (columns.size() == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The last column of a board cannot be deleted");
        }

        BoardColumn target = moveTo == null ? null : findColumn(moveTo, email);
        if (target != null) {
            requireSameBoard(column, target);
            if (target.getId().equals(column.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move tasks into the deleted column");
            }
        }

        List<Task> tasks = taskRepository.findByBoardColumnOrderByPosition(column);
        if (!tasks.isEmpty()) {
            if (target != null) {
                // Keep the order and append the tasks after the ones already in the target.
                int next = taskRepository.countByBoardColumn(target);
                for (Task task : tasks) {
                    ruleEngine.taskLeft(task, column);
                    task.setBoardColumn(target);
                    task.setPosition(next++);
                    historyService.recordMove(task, column, target, email);
                    ruleEngine.taskEntered(task, email);
                }
            } else if (deleteTasks) {
                tasks.forEach(task -> historyService.recordDeleted(task, email));
                taskRepository.deleteAll(tasks);
            } else {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Column is not empty: pass moveTo or deleteTasks=true");
            }
        }

        columns.removeIf(c -> c.getId().equals(column.getId()));
        ruleRepository.deleteByColumn(column);
        columnRepository.delete(column);
        for (int i = 0; i < columns.size(); i++) {
            columns.get(i).setPosition(i);
        }
        changed(column.getBoard());
    }

    /**
     * Moves a task to {@code position} within a column of the same board.
     * A position beyond the end of the column puts the task last. Leaving a column and entering another runs their rules.
     */
    @Transactional
    public Task moveTask(int taskId, int columnId, int position, String email) {
        Task task = findTask(taskId, email);
        BoardColumn source = task.getBoardColumn();
        BoardColumn target = findColumn(columnId, email);
        requireSameBoard(source, target);

        List<Task> sourceTasks = new ArrayList<>(taskRepository.findByBoardColumnOrderByPosition(source));
        sourceTasks.removeIf(t -> t.getId().equals(task.getId()));

        boolean sameColumn = source.getId().equals(target.getId());
        List<Task> targetTasks = sameColumn
                ? sourceTasks
                : new ArrayList<>(taskRepository.findByBoardColumnOrderByPosition(target));

        int index = Math.max(0, Math.min(position, targetTasks.size()));
        targetTasks.add(index, task);
        task.setBoardColumn(target);
        historyService.recordMove(task, source, target, email);

        renumber(sourceTasks);
        if (!sameColumn) {
            renumber(targetTasks);
            ruleEngine.taskLeft(task, source);
            ruleEngine.taskEntered(task, email);
        }
        changed(target.getBoard());
        return task;
    }

    /** Tells open views of the board (see {@link BoardEvents}); sent once the transaction committed. */
    public void changed(Board board) {
        events.publishEvent(new BoardChanged(board.getId()));
    }

    /** True if the user owns the board or is one of its members. The one place that decides this. */
    public boolean canAccess(Board board, String email) {
        return isOwner(board, email) || memberRepository.existsByBoardAndUserEmail(board, email);
    }

    public boolean isOwner(Board board, String email) {
        return email != null && email.equals(board.getUser().getEmail());
    }

    /** A board the user owns or is a member of; else 404, so boards of others stay invisible. */
    @Transactional(readOnly = true)
    public Board findBoard(int boardId, String email) {
        return boardRepository.findById(boardId)
                .filter(board -> canAccess(board, email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board not found"));
    }

    /** A board the user owns; members get 404 too. For deleting, sharing and managing members. */
    @Transactional(readOnly = true)
    public Board findOwnBoard(int boardId, String email) {
        return boardRepository.findById(boardId)
                .filter(board -> isOwner(board, email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board not found"));
    }

    @Transactional(readOnly = true)
    public BoardColumn findColumn(int columnId, String email) {
        return columnRepository.findById(columnId)
                .filter(column -> canAccess(column.getBoard(), email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Column not found"));
    }

    @Transactional(readOnly = true)
    public Task findTask(int taskId, String email) {
        return taskRepository.findById(taskId)
                .filter(task -> canAccess(task.getBoardColumn().getBoard(), email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }

    private static void requireSameBoard(BoardColumn a, BoardColumn b) {
        if (!Objects.equals(a.getBoard().getId(), b.getBoard().getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Columns belong to different boards");
        }
    }

    private static void renumber(List<Task> tasks) {
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).setPosition(i);
        }
    }

    // Stored in lower case so the frontend can compare colors directly.
    private static String validColor(String color) {
        if (color == null) {
            return null;
        }
        if (!HEX_COLOR.matcher(color).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Color must look like #rrggbb");
        }
        return color.toLowerCase();
    }

    // Board and column names: trimmed, 1 to MAX_NAME_LENGTH characters.
    private static String validName(String name, String what) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + " name must not be empty");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    what + " name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }
}
