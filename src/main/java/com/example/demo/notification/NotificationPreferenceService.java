package com.example.demo.notification;

import com.example.demo.notification.NotificationPreference.Delivery;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The user's choices for channels besides the bell; for now mail. */
@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;
    private final UserRepository userRepository;
    private final NotificationMail mail;

    NotificationPreferenceService(NotificationPreferenceRepository repository, UserRepository userRepository,
                                  NotificationMail mail) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.mail = mail;
    }

    /** available: mail is set up on this server; address: where mails go (the login's address). */
    public record MailSettings(boolean available, String address, Delivery delivery) {
    }

    @Transactional(readOnly = true)
    public MailSettings mailSettings(String email) {
        User user = findUser(email);
        Delivery delivery = repository.findByUserIdAndChannel(user.getId(), NotificationPreference.MAIL)
                .map(NotificationPreference::getDelivery).orElse(Delivery.OFF);
        return new MailSettings(mail.available(), user.getEmail(), delivery);
    }

    @Transactional
    public MailSettings setMailDelivery(String email, String delivery) {
        Delivery chosen;
        try {
            chosen = Delivery.valueOf(String.valueOf(delivery));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery must be one of OFF, IMMEDIATE, DAILY, WEEKLY");
        }
        User user = findUser(email);
        if (chosen != Delivery.OFF && !mail.available()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mail is not set up on this server");
        }
        NotificationPreference preference = repository.findByUserIdAndChannel(user.getId(), NotificationPreference.MAIL)
                .orElseGet(() -> repository.save(new NotificationPreference(user, NotificationPreference.MAIL)));
        preference.setDelivery(chosen);
        return new MailSettings(mail.available(), user.getEmail(), chosen);
    }

    /** From the link in a mail, without a login. Unknown tokens are "not found". */
    @Transactional
    public void unsubscribe(String token) {
        repository.findByUnsubscribeToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link"))
                .setDelivery(Delivery.OFF);
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
