package com.example.demo.notification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDispatcherTests {

    @Test
    void everyChannelGetsTheMessageEvenIfOneFails() {
        List<NotificationMessage> delivered = new ArrayList<>();
        NotificationChannel failing = message -> {
            throw new IllegalStateException("mail server down");
        };
        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(failing, delivered::add));
        NotificationMessage message = new NotificationMessage(1, 2, "alice@example.com", "Hi", 3, null);

        dispatcher.onCreated(new NotificationCreated(message));

        assertThat(delivered).containsExactly(message);
    }
}
