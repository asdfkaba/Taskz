package com.example.demo.notification;

/**
 * A stored notification as channels get it, after the commit and without JPA entities.
 * boardId and taskId are null if it isn't about a board or task.
 */
public record NotificationMessage(int id, int userId, String email, String text, Integer boardId, Integer taskId) {
}
