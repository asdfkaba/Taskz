package com.example.demo.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notification mails (notifications.mail.*), sent over SMTP (spring.mail.*). Without a mail host,
 * a sender or the address of the app, mail is off and the settings say so.
 *
 * @param from    sender, e.g. "Taskz &lt;taskz@example.com&gt;"; its domain needs SPF and DKIM
 * @param baseUrl where the app is, e.g. https://taskz.example.com, for the links in mails
 */
@ConfigurationProperties("notifications.mail")
public record NotificationMailProperties(String from, String baseUrl) {
}
