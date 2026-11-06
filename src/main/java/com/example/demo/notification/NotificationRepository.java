package com.example.demo.notification;

import com.example.demo.board.Board;
import com.example.demo.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Integer> {

    List<Notification> findTop50ByUserOrderByCreatedDescIdDesc(User user);

    long countByUserAndSeenAtIsNull(User user);

    // For a summary mail: still unread and new since the last one.
    List<Notification> findByUserAndReadAtIsNullAndCreatedAfterOrderByCreatedDescIdDesc(User user, Instant after);

    @Modifying
    @Query("update Notification n set n.seenAt = :now where n.user = :user and n.seenAt is null")
    int markAllSeen(User user, Instant now);

    // Read ones are seen too.
    @Modifying
    @Query("""
            update Notification n set n.readAt = :now, n.seenAt = coalesce(n.seenAt, :now)
            where n.user = :user and n.readAt is null""")
    int markAllRead(User user, Instant now);

    // Loads and removes them, so none stays in the session pointing at the deleted board.
    void deleteByBoard(Board board);
}
