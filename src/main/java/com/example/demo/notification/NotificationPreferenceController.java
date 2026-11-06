package com.example.demo.notification;

import com.example.demo.notification.NotificationPreferenceService.MailSettings;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationPreferenceController {

    public record DeliveryRequest(String delivery) {
    }

    private final NotificationPreferenceService service;

    public NotificationPreferenceController(NotificationPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/account/notifications/mail")
    public MailSettings mailSettings() {
        return service.mailSettings(currentUserEmail());
    }

    @PutMapping("/account/notifications/mail")
    public MailSettings setMailDelivery(@RequestBody DeliveryRequest request) {
        return service.setMailDelivery(currentUserEmail(), request.delivery());
    }

    // Without a login: the link in every mail (see WebSecurityConfig). POST, so mail scanners that
    // open links don't unsubscribe anyone; the page behind the link asks first.
    @PostMapping("/mail/unsubscribe/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@PathVariable("token") String token) {
        service.unsubscribe(token);
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
