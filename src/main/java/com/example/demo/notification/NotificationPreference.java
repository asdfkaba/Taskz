package com.example.demo.notification;

import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/** How a user wants one channel's notifications (table notification_preference, see V14). */
@Entity
@Table(name = "notification_preference",
        uniqueConstraints = @UniqueConstraint(name = "notification_preference_unique", columnNames = {"user_id", "channel"}))
@Getter
@NoArgsConstructor
public class NotificationPreference {

    public enum Delivery {
        OFF(null), IMMEDIATE(null), DAILY(Duration.ofDays(1)), WEEKLY(Duration.ofDays(7));

        // How often a summary goes out; null if there is no summary.
        final Duration period;

        Delivery(Duration period) {
            this.period = period;
        }
    }

    public static final String MAIL = "MAIL";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, length = 32)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Delivery delivery;

    private Instant lastDigestAt;

    // Random, in the mail's unsubscribe link; it can only switch this channel off.
    @Column(nullable = false, unique = true, length = 64)
    private String unsubscribeToken;

    NotificationPreference(User user, String channel) {
        this.user = user;
        this.channel = channel;
        this.delivery = Delivery.OFF;
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        this.unsubscribeToken = HexFormat.of().formatHex(bytes);
    }

    /** A summary starts with what comes from now on, so nothing mailed before is sent again. */
    void setDelivery(Delivery delivery) {
        if (delivery.period != null && this.delivery != delivery) {
            lastDigestAt = Instant.now();
        }
        this.delivery = delivery;
    }

    void digestSent(Instant at) {
        lastDigestAt = at;
    }

    boolean digestDue(Instant now) {
        return delivery.period != null && (lastDigestAt == null || !lastDigestAt.plus(delivery.period).isAfter(now));
    }
}
