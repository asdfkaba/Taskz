package com.example.demo.notification;

import com.example.demo.board.Board;
import com.example.demo.board.BoardDtos.BoardSummary;
import com.example.demo.task.Task;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Notifications for users. Each one is stored (the list under the bell) and then, after the commit,
 * handed to every {@link NotificationChannel} (see NotificationDispatcher).
 */
@Service
public class NotificationService {

    // Under the bell: the newest ones; older ones stay in the table.
    static final int LIST_SIZE = 50;

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    // No BoardService here: RuleEngine needs this service, and BoardService needs RuleEngine.
    public NotificationService(NotificationRepository repository, UserRepository userRepository,
                               ApplicationEventPublisher events) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.events = events;
    }

    /**
     * boardSlug and taskId link the entry for the user; both null if they can't open the board (any
     * more), taskId also when the task is gone.
     */
    public record NotificationDto(Integer id, String text, String boardName, String boardSlug, Integer taskId,
                                  String taskTitle, Instant created, boolean read) {
    }

    // unseen: for the count on the bell; unread ones keep their mark in the list until read.
    public record NotificationList(List<NotificationDto> items, long unseen) {
    }

    /**
     * Stores the text for each recipient. Board and task are optional; a task brings its board. The
     * text is cut to {@value Notification#MAX_TEXT_LENGTH} characters.
     */
    @Transactional
    public void notify(Collection<User> recipients, String text, Board board, Task task) {
        Board about = task != null ? task.getBoardColumn().getBoard() : board;
        String stored = text.strip();
        if (stored.length() > Notification.MAX_TEXT_LENGTH) {
            stored = stored.substring(0, Notification.MAX_TEXT_LENGTH - 1) + "…";
        }
        for (User user : recipients) {
            Notification n = repository.save(new Notification(user, about, task, stored));
            events.publishEvent(new NotificationCreated(new NotificationMessage(n.getId(), user.getId(),
                    user.getEmail(), stored, about == null ? null : about.getId(), task == null ? null : task.getId())));
        }
    }

    /** boards: the user's board list (BoardService.summariesOf), for the links. */
    @Transactional(readOnly = true)
    public NotificationList notificationsOf(String email, List<BoardSummary> boardList) {
        User user = findUser(email);
        Map<Integer, BoardSummary> boards = boardList.stream()
                .collect(Collectors.toMap(BoardSummary::id, Function.identity()));
        List<NotificationDto> items = repository.findTop50ByUserOrderByCreatedDescIdDesc(user).stream()
                .map(n -> toDto(n, boards))
                .toList();
        return new NotificationList(items, repository.countByUserAndSeenAtIsNull(user));
    }

    static NotificationDto toDto(Notification n, Map<Integer, BoardSummary> boards) {
        Board board = n.getBoard();
        BoardSummary open = board == null ? null : boards.get(board.getId());
        Task task = open == null ? null : n.getTask();
        return new NotificationDto(n.getId(), n.getText(), board == null ? null : board.getName(),
                open == null ? null : open.slug(), task == null ? null : task.getId(),
                task == null ? null : task.getTitle(), n.getCreated(), n.getReadAt() != null);
    }

    /** Read: the user dealt with it (opened it, or "Mark all read"). Others' are "not found". */
    @Transactional
    public void markRead(int id, String email) {
        repository.findById(id)
                .filter(n -> n.getUser().getEmail().equals(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"))
                .markRead();
    }

    /** Seen: the user opened the bell; the count only shows the ones that came after. */
    @Transactional
    public void markAllSeen(String email) {
        repository.markAllSeen(findUser(email), Instant.now());
    }

    @Transactional
    public void markAllRead(String email) {
        repository.markAllRead(findUser(email), Instant.now());
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
