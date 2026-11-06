package com.example.demo.notification;

/** Published when a notification is stored; NotificationDispatcher hands it to the channels after the commit. */
record NotificationCreated(NotificationMessage message) {
}
