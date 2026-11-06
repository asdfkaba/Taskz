package com.example.demo.notification;

import com.example.demo.notification.NotificationService.NotificationDto;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

/** Writes and sends notification mails: plain text, with links into the app and to unsubscribe. */
@Component
class NotificationMail {

    private final ObjectProvider<JavaMailSender> sender;
    private final NotificationMailProperties properties;

    NotificationMail(ObjectProvider<JavaMailSender> sender, NotificationMailProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    /** Spring only has a JavaMailSender with spring.mail.host set. */
    boolean available() {
        return sender.getIfAvailable() != null && notBlank(properties.from()) && notBlank(properties.baseUrl());
    }

    void send(String to, String unsubscribeToken, String subject, List<NotificationDto> items) {
        JavaMailSender mailSender = sender.getObject();
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body(baseUrl(), unsubscribeToken, items));
        } catch (MessagingException e) {
            throw new IllegalStateException("Could not write the mail", e);
        }
        mailSender.send(message);
    }

    // One block per notification: text, then where it is and the link.
    static String body(String baseUrl, String unsubscribeToken, List<NotificationDto> items) {
        StringBuilder text = new StringBuilder();
        for (NotificationDto item : items) {
            text.append(item.text()).append('\n');
            String where = String.join(" · ", Stream.of(item.taskTitle(), item.boardName())
                    .filter(s -> s != null && !s.isBlank()).toList());
            if (!where.isEmpty()) {
                text.append(where).append('\n');
            }
            if (item.boardSlug() != null) {
                text.append(baseUrl).append("/boards/").append(item.boardSlug())
                        .append(item.taskId() == null ? "" : "?task=" + item.taskId()).append('\n');
            }
            text.append('\n');
        }
        text.append("--\n")
                .append("Change how you get notifications: ").append(baseUrl).append("/account\n")
                .append("Stop these mails: ").append(baseUrl).append("/unsubscribe/").append(unsubscribeToken).append('\n');
        return text.toString();
    }

    private String baseUrl() {
        String url = properties.baseUrl().strip();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
