package com.example.demo.board;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Open live views of boards (server-sent events), by board. A change only says "changed"; the
 * view loads the board again, so it can't miss or misapply a detail. Logged-in viewers (owner,
 * members) also get "presence": id and name of the others who have the board open. Viewers of a
 * share link are anonymous and never see names.
 */
@Component
public class BoardEvents {

    /** Who has a board open; null for a share link. */
    record Viewer(int userId, String name) {
    }

    private record Listener(SseEmitter emitter, Viewer viewer) {
    }

    /** One of the others in a "presence" event; the page draws their icon from the id. */
    record Person(int id, String name) {
    }

    private final Map<Integer, Set<Listener>> listeners = new ConcurrentHashMap<>();

    /** A stream for a share link's viewer, open until they leave or the link stops working. */
    SseEmitter subscribe(int boardId) {
        return subscribe(boardId, null);
    }

    /** A stream for the owner or a member, open until they leave or lose access. */
    SseEmitter subscribe(int boardId, Viewer viewer) {
        return subscribe(boardId, viewer, new SseEmitter(0L)); // no timeout: dead connections fail the heartbeat
    }

    // The emitter is passed in only so tests can use one whose client is gone.
    SseEmitter subscribe(int boardId, Viewer viewer, SseEmitter emitter) {
        Listener listener = new Listener(emitter, viewer);
        listeners.compute(boardId, (id, set) -> {
            Set<Listener> all = set != null ? set : ConcurrentHashMap.newKeySet();
            all.add(listener);
            return all;
        });
        Runnable remove = () -> {
            boolean[] removed = {false};
            listeners.computeIfPresent(boardId, (id, set) -> {
                removed[0] = set.remove(listener);
                return set.isEmpty() ? null : set;
            });
            if (removed[0] && viewer != null) {
                sendPresence(boardId);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        // Sent right away, so the browser sees the stream open (and reloads after a reconnect).
        if (!send(listener, SseEmitter.event().comment("open"))) {
            drop(boardId, List.of(listener));
        }
        if (viewer != null) {
            sendPresence(boardId);
        }
        return emitter;
    }

    // After the commit, so the reload sees the change. Also for changes made outside a transaction.
    @TransactionalEventListener(fallbackExecution = true)
    void onChange(BoardChanged event) {
        // Browsers drop events without data.
        sendToAll(event.boardId(), listenersOf(event.boardId(), l -> true),
                l -> SseEmitter.event().name("changed").data("changed"));
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onShareEnded(BoardShareEnded event) {
        end(event.boardId(), l -> l.viewer() == null);
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onAccessEnded(BoardAccessEnded event) {
        end(event.boardId(), l -> l.viewer() != null
                && (event.userId() == null || l.viewer().userId() == event.userId()));
    }

    // Taken off right away, not only once the server finished the stream, so the others see them
    // gone at once.
    private void end(int boardId, Predicate<Listener> which) {
        List<Listener> ending = listenersOf(boardId, which);
        listeners.computeIfPresent(boardId, (id, set) -> {
            ending.forEach(set::remove);
            return set.isEmpty() ? null : set;
        });
        ending.forEach(BoardEvents::complete);
        if (ending.stream().anyMatch(l -> l.viewer() != null)) {
            sendPresence(boardId);
        }
    }

    // nginx closes a proxied connection after 60 s without data.
    @Scheduled(fixedRateString = "PT30S")
    void heartbeat() {
        listeners.keySet().forEach(boardId ->
                sendToAll(boardId, listenersOf(boardId, l -> true), l -> SseEmitter.event().comment("ping")));
    }

    // Each logged-in viewer gets the others, once per person (several tabs count once), in the
    // order they came.
    private void sendPresence(int boardId) {
        List<Listener> loggedIn = listenersOf(boardId, l -> l.viewer() != null);
        Map<Integer, String> people = new LinkedHashMap<>();
        loggedIn.forEach(l -> people.putIfAbsent(l.viewer().userId(), l.viewer().name()));
        sendToAll(boardId, loggedIn, listener -> {
            List<Person> others = people.entrySet().stream()
                    .filter(person -> person.getKey() != listener.viewer().userId())
                    .map(person -> new Person(person.getKey(), person.getValue() == null ? "Someone" : person.getValue()))
                    .toList();
            return SseEmitter.event().name("presence").data(others, MediaType.APPLICATION_JSON);
        });
    }

    private List<Listener> listenersOf(int boardId, Predicate<Listener> filter) {
        return listeners.getOrDefault(boardId, Set.of()).stream().filter(filter).toList();
    }

    // Listeners whose client is gone are dropped after the round, so a new presence list can't be
    // overtaken by the rest of an older one.
    private void sendToAll(int boardId, List<Listener> to, Function<Listener, SseEmitter.SseEventBuilder> event) {
        List<Listener> gone = new ArrayList<>();
        for (Listener listener : to) {
            if (!send(listener, event.apply(listener))) {
                gone.add(listener);
            }
        }
        drop(boardId, gone);
    }

    /**
     * False if the client is gone. Such a listener is only dropped (see drop): Tomcat ends the
     * request itself and refuses completeWithError from this thread (IllegalStateException), which
     * would otherwise fail the request of whoever caused the send, e.g. saving a task.
     */
    private static boolean send(Listener listener, SseEmitter.SseEventBuilder event) {
        try {
            listener.emitter().send(event);
            return true;
        } catch (IOException | RuntimeException gone) {
            return false;
        }
    }

    private void drop(int boardId, List<Listener> gone) {
        if (gone.isEmpty()) {
            return;
        }
        listeners.computeIfPresent(boardId, (id, set) -> {
            gone.forEach(set::remove);
            return set.isEmpty() ? null : set;
        });
        if (gone.stream().anyMatch(l -> l.viewer() != null)) {
            sendPresence(boardId);
        }
    }

    // Ending a stream whose client is gone fails the same way; it is over anyway.
    private static void complete(Listener listener) {
        try {
            listener.emitter().complete();
        } catch (RuntimeException alreadyOver) {
            // nothing left to end
        }
    }
}
