package com.example.demo.notification;

import com.example.demo.board.Board;
import com.example.demo.task.Task;
import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** A message for one user under the bell (table notification, see V13__notifications.sql). */
@Entity
@Table(name = "notification", indexes = @Index(name = "notification_user_created", columnList = "user_id, created"))
@Getter
@NoArgsConstructor
public class Notification {

    static final int MAX_TEXT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    // What it is about, if anything; a task's board is set too.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Task task;

    @Column(nullable = false, length = MAX_TEXT_LENGTH)
    private String text;

    @Column(nullable = false)
    private Instant created;

    // When the user first opened the bell after it came (it no longer counts on the bell); null before.
    private Instant seenAt;

    // When the user dealt with it: opened it or marked all read; null while unread.
    private Instant readAt;

    Notification(User user, Board board, Task task, String text) {
        this.user = user;
        this.board = board;
        this.task = task;
        this.text = text;
        this.created = Instant.now();
    }

    void markRead() {
        Instant now = Instant.now();
        if (seenAt == null) {
            seenAt = now;
        }
        if (readAt == null) {
            readAt = now;
        }
    }
}
