package com.example.demo.notification;

import com.example.demo.board.BoardEvents;
import org.springframework.stereotype.Component;

/**
 * The bell: the notification is already stored, so the page only needs a nudge to load it. It goes
 * through the user's open board streams; pages without one load it when they open or regain focus.
 */
@Component
class WebUiChannel implements NotificationChannel {

    private final BoardEvents boardEvents;

    WebUiChannel(BoardEvents boardEvents) {
        this.boardEvents = boardEvents;
    }

    @Override
    public void deliver(NotificationMessage message) {
        boardEvents.notifyUser(message.userId());
    }
}
