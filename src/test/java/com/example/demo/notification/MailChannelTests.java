package com.example.demo.notification;

import com.example.demo.board.Board;
import com.example.demo.board.BoardColumnRepository;
import com.example.demo.board.BoardService;
import com.example.demo.notification.NotificationPreference.Delivery;
import com.example.demo.task.RuleEngine;
import com.example.demo.task.Task;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({NotificationService.class, BoardService.class, RuleEngine.class, TaskHistoryService.class})
class MailChannelTests {

    static final String ALICE = "alice@example.com";
    static final String BASE = "https://taskz.example.com";

    /** Keeps the mails instead of sending them. */
    static class Outbox extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();

        @Override
        public void send(MimeMessage message) {
            sent.add(message);
        }
    }

    @Autowired
    NotificationService notificationService;
    @Autowired
    BoardService boardService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    NotificationPreferenceRepository preferences;
    @Autowired
    PlatformTransactionManager transactions;

    Outbox outbox = new Outbox();
    MailChannel channel;
    NotificationPreferenceService settings;
    User alice;
    Board board;
    Task task;

    private NotificationMail mail(JavaMailSender sender) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(sender == null ? Map.of() : Map.of("mail", sender));
        return new NotificationMail(beans.getBeanProvider(JavaMailSender.class),
                new NotificationMailProperties("Taskz <taskz@example.com>", BASE + "/"));
    }

    @BeforeEach
    void setUp() {
        NotificationMail mail = mail(outbox);
        channel = new MailChannel(preferences, notifications, boardService, mail, transactions, Runnable::run);
        settings = new NotificationPreferenceService(preferences, userRepository, mail);
        alice = userRepository.save(new User("Alice", ALICE));
        board = boardService.createDefaultBoard(alice);
        task = new Task("Acme", null, null, null);
        task.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        taskRepository.save(task);
    }

    // Like NotificationDispatcher after the commit.
    private void notifyAlice(String text) {
        notificationService.notify(List.of(alice), text, null, task);
        Notification n = notifications.findTop50ByUserOrderByCreatedDescIdDesc(alice).get(0);
        channel.deliver(new NotificationMessage(n.getId(), alice.getId(), ALICE, text, board.getId(), task.getId()));
    }

    private static String text(MimeMessage message) throws Exception {
        return (String) message.getContent();
    }

    @Test
    void rightAwaySendsEachNotificationWithLinks() throws Exception {
        notifyAlice("Not mailed: mail is off");
        assertThat(outbox.sent).isEmpty();

        settings.setMailDelivery(ALICE, "IMMEDIATE");
        notifyAlice("Please review the offer");

        assertThat(outbox.sent).singleElement().satisfies(message -> {
            assertThat(message.getSubject()).isEqualTo("Taskz: Please review the offer");
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo(ALICE);
            String body = text(message);
            assertThat(body).contains("Please review the offer", "Acme · " + board.getName(),
                    BASE + "/boards/" + board.getSlug() + "?task=" + task.getId(), BASE + "/account");
            String token = preferences.findByUserIdAndChannel(alice.getId(), NotificationPreference.MAIL).orElseThrow().getUnsubscribeToken();
            assertThat(body).contains(BASE + "/unsubscribe/" + token);
        });
    }

    @Test
    void aSummaryHoldsWhatIsUnreadAndNewSinceTheLastOne() throws Exception {
        settings.setMailDelivery(ALICE, "IMMEDIATE");
        notifyAlice("Mailed right away before");
        outbox.sent.clear();

        settings.setMailDelivery(ALICE, "DAILY");
        NotificationPreference preference = preferences.findByUserIdAndChannel(alice.getId(), NotificationPreference.MAIL).orElseThrow();
        Instant start = preference.getLastDigestAt();
        assertThat(start).isNotNull();
        notifyAlice("One");
        notifyAlice("Two");
        notifyAlice("Read already");
        assertThat(outbox.sent).isEmpty();
        notificationService.markRead(notifications.findTop50ByUserOrderByCreatedDescIdDesc(alice).get(0).getId(), ALICE);

        channel.sendDueSummaries(start.plus(Duration.ofHours(23)));
        assertThat(outbox.sent).isEmpty();

        Instant day = start.plus(Duration.ofHours(25));
        channel.sendDueSummaries(day);
        assertThat(outbox.sent).singleElement().satisfies(message -> {
            assertThat(message.getSubject()).isEqualTo("Taskz: 2 new notifications");
            assertThat(text(message)).contains("One", "Two").doesNotContain("Read already", "Mailed right away before");
        });

        // Due again a day later, but nothing new: no mail.
        channel.sendDueSummaries(day.plus(Duration.ofHours(25)));
        assertThat(outbox.sent).hasSize(1);
    }

    @Test
    void theLinkInAMailSwitchesMailOff() {
        settings.setMailDelivery(ALICE, "WEEKLY");
        String token = preferences.findByUserIdAndChannel(alice.getId(), NotificationPreference.MAIL).orElseThrow().getUnsubscribeToken();

        settings.unsubscribe(token);

        assertThat(settings.mailSettings(ALICE).delivery()).isEqualTo(Delivery.OFF);
        assertThatThrownBy(() -> settings.unsubscribe("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void withoutAMailServerMailCantBeSwitchedOnAndNothingIsSent() {
        NotificationMail none = mail(null);
        NotificationPreferenceService noMail = new NotificationPreferenceService(preferences, userRepository, none);
        assertThat(noMail.mailSettings(ALICE).available()).isFalse();
        assertThatThrownBy(() -> noMail.setMailDelivery(ALICE, "DAILY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> settings.setMailDelivery(ALICE, "HOURLY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(noMail.setMailDelivery(ALICE, "OFF").delivery()).isEqualTo(Delivery.OFF);
    }
}
