package com.example.demo.notification;

import com.example.demo.board.BoardService;
import com.example.demo.notification.NotificationService.NotificationList;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

// The logged-in user's own notifications.
@RestController
public class NotificationController {

    private final NotificationService service;
    private final BoardService boardService;

    public NotificationController(NotificationService service, BoardService boardService) {
        this.service = service;
        this.boardService = boardService;
    }

    // Newest first, with the number of unseen ones for the bell.
    @GetMapping("/notifications")
    public NotificationList getNotifications() {
        String email = currentUserEmail();
        return service.notificationsOf(email, boardService.summariesOf(email));
    }

    // When the bell is opened.
    @PostMapping("/notifications/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllSeen() {
        service.markAllSeen(currentUserEmail());
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable("id") int id) {
        service.markRead(id, currentUserEmail());
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        service.markAllRead(currentUserEmail());
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
