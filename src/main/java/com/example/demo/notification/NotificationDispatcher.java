package com.example.demo.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/** Hands each stored notification to all channels, after the commit so a rollback sends nothing. */
@Component
class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<NotificationChannel> channels;

    NotificationDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onCreated(NotificationCreated event) {
        for (NotificationChannel channel : channels) {
            try {
                channel.deliver(event.message());
            } catch (RuntimeException e) {
                // The notification stays stored and shows under the bell.
                log.warn("Channel {} could not deliver notification {}", channel.getClass().getSimpleName(),
                        event.message().id(), e);
            }
        }
    }
}
