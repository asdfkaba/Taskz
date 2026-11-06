package com.example.demo.board;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A viewer whose browser is gone must not break the requests of others (seen in production:
 * a new viewer's request failed with 500 because of someone else's dead connection).
 */
class BoardEventsGoneClientTests {

    /** Keeps what was sent, as text. */
    static class Recording extends SseEmitter {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) {
            sent.add(event.build().stream().map(d -> String.valueOf(d.getData())).collect(Collectors.joining()));
        }
    }

    /** A client that left: sending fails, and Tomcat refuses ending it from another thread. */
    static class Gone extends SseEmitter {
        boolean gone;

        @Override
        public void send(SseEventBuilder event) throws IOException {
            if (gone) {
                throw new IOException("Broken pipe");
            }
        }

        @Override
        public void complete() {
            if (gone) {
                throw new IllegalStateException("AsyncContext used after an error");
            }
        }

        @Override
        public void completeWithError(Throwable ex) {
            throw new IllegalStateException("AsyncContext used after an error");
        }
    }

    @Test
    void aGoneViewerIsDroppedWithoutFailingOthers() {
        BoardEvents events = new BoardEvents();
        Recording alice = new Recording();
        Gone bob = new Gone();
        events.subscribe(7, new BoardEvents.Viewer(1, "Alice"), alice);
        events.subscribe(7, new BoardEvents.Viewer(2, "Bob"), bob);
        bob.gone = true;

        assertThatCode(() -> {
            events.subscribe(7, new BoardEvents.Viewer(3, "Carol"), new Recording());
            events.onChange(new BoardChanged(7));
            events.heartbeat();
            events.onAccessEnded(new BoardAccessEnded(7, null));
        }).doesNotThrowAnyException();

        // Alice saw Bob come, and go once his connection failed.
        List<String> presence = alice.sent.stream().filter(sent -> sent.startsWith("event:presence")).toList();
        assertThat(presence).anyMatch(sent -> sent.contains("name=Bob"));
        assertThat(presence.get(presence.size() - 1)).contains("name=Carol").doesNotContain("name=Bob");
        assertThat(alice.sent).anyMatch(sent -> sent.startsWith("event:changed"));
    }

    @Test
    void aNotificationReachesOnlyTheUsersOwnStreamsOnAnyBoard() {
        BoardEvents events = new BoardEvents();
        Recording aliceHere = new Recording();
        Recording aliceElsewhere = new Recording();
        Recording bob = new Recording();
        Recording shareLink = new Recording();
        events.subscribe(7, new BoardEvents.Viewer(1, "Alice"), aliceHere);
        events.subscribe(8, new BoardEvents.Viewer(1, "Alice"), aliceElsewhere);
        events.subscribe(7, new BoardEvents.Viewer(2, "Bob"), bob);
        events.subscribe(7, null, shareLink);

        events.notifyUser(1);

        assertThat(aliceHere.sent).anyMatch(sent -> sent.startsWith("event:notification"));
        assertThat(aliceElsewhere.sent).anyMatch(sent -> sent.startsWith("event:notification"));
        assertThat(bob.sent).noneMatch(sent -> sent.startsWith("event:notification"));
        assertThat(shareLink.sent).noneMatch(sent -> sent.startsWith("event:notification"));
    }
}
