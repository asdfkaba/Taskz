package com.example.demo.notification;

/**
 * A way to deliver notifications (web UI now; mail, chat or webhooks later). Every channel bean
 * gets every notification once it is stored; a failing channel doesn't stop the others.
 */
public interface NotificationChannel {

    void deliver(NotificationMessage message);
}
