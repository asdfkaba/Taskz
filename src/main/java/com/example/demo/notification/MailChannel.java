package com.example.demo.notification;

import com.example.demo.board.BoardDtos.BoardSummary;
import com.example.demo.board.BoardService;
import com.example.demo.notification.NotificationPreference.Delivery;
import com.example.demo.notification.NotificationService.NotificationDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mail, as each user chose in their settings: each notification right away, or a summary of the
 * unread ones once a day or week. Mails go out on their own thread, so a slow mail server doesn't
 * hold up whoever caused the notification.
 */
@Component
class MailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(MailChannel.class);
    private static final int SUBJECT_LENGTH = 80;

    private final NotificationPreferenceRepository preferences;
    private final NotificationRepository notifications;
    private final BoardService boardService;
    private final NotificationMail mail;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate writing;
    private final Executor executor;

    @Autowired
    MailChannel(NotificationPreferenceRepository preferences, NotificationRepository notifications,
                BoardService boardService, NotificationMail mail, PlatformTransactionManager transactions) {
        this(preferences, notifications, boardService, mail, transactions, Executors.newSingleThreadExecutor());
    }

    // Tests pass a direct executor.
    MailChannel(NotificationPreferenceRepository preferences, NotificationRepository notifications,
                BoardService boardService, NotificationMail mail, PlatformTransactionManager transactions,
                Executor executor) {
        this.preferences = preferences;
        this.notifications = notifications;
        this.boardService = boardService;
        this.mail = mail;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.writing = new TransactionTemplate(transactions);
        this.executor = executor;
    }

    private record Outgoing(String to, String token, String subject, List<NotificationDto> items) {
    }

    @Override
    public void deliver(NotificationMessage message) {
        if (!mail.available()) {
            return;
        }
        Outgoing outgoing = readOnly.execute(status -> preferences.findByUserIdAndChannel(message.userId(), NotificationPreference.MAIL)
                .filter(p -> p.getDelivery() == Delivery.IMMEDIATE)
                .flatMap(p -> notifications.findById(message.id())
                        .map(n -> new Outgoing(message.email(), p.getUnsubscribeToken(), subject(n.getText()),
                                List.of(NotificationService.toDto(n, boardsOf(message.email()))))))
                .orElse(null));
        if (outgoing != null) {
            send(outgoing);
        }
    }

    /** Summaries that are due: what is still unread and came since the last one. Nothing new, no mail. */
    @Scheduled(fixedRateString = "PT1H", initialDelayString = "PT1M")
    void sendDueSummaries() {
        sendDueSummaries(Instant.now());
    }

    // The time is passed in for tests.
    void sendDueSummaries(Instant now) {
        if (!mail.available()) {
            return;
        }
        List<Outgoing> due = writing.execute(status -> preferences
                .findByChannelAndDeliveryIn(NotificationPreference.MAIL, List.of(Delivery.DAILY, Delivery.WEEKLY)).stream()
                .filter(p -> p.digestDue(now))
                .map(p -> summary(p, now))
                .filter(o -> o != null)
                .toList());
        // shortcut: the summary counts as sent before the mail goes out; a failed send loses it (the bell still has it).
        due.forEach(this::send);
    }

    private Outgoing summary(NotificationPreference preference, Instant now) {
        Instant since = preference.getLastDigestAt() == null ? Instant.EPOCH : preference.getLastDigestAt();
        preference.digestSent(now);
        String email = preference.getUser().getEmail();
        List<Notification> unread = notifications.findByUserAndReadAtIsNullAndCreatedAfterOrderByCreatedDescIdDesc(
                preference.getUser(), since);
        if (unread.isEmpty() || email == null) {
            return null;
        }
        Map<Integer, BoardSummary> boards = boardsOf(email);
        String subject = "Taskz: " + unread.size() + (unread.size() == 1 ? " new notification" : " new notifications");
        return new Outgoing(email, preference.getUnsubscribeToken(), subject,
                unread.stream().map(n -> NotificationService.toDto(n, boards)).toList());
    }

    private Map<Integer, BoardSummary> boardsOf(String email) {
        return boardService.summariesOf(email).stream().collect(Collectors.toMap(BoardSummary::id, Function.identity()));
    }

    private void send(Outgoing outgoing) {
        executor.execute(() -> {
            try {
                mail.send(outgoing.to(), outgoing.token(), outgoing.subject(), outgoing.items());
            } catch (RuntimeException e) {
                log.warn("Could not send a notification mail: {}", outgoing.subject(), e);
            }
        });
    }

    private static String subject(String text) {
        String line = text.lines().findFirst().orElse("");
        return "Taskz: " + (line.length() > SUBJECT_LENGTH ? line.substring(0, SUBJECT_LENGTH - 1) + "…" : line);
    }

    @PreDestroy
    void stop() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
}
