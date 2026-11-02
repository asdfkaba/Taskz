package com.example.demo.board;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Open live views of boards (server-sent events), by board. A change only says "changed"; the
 * view loads the board again, so it can't miss or misapply a detail. Logged-in viewers (owner,
 * members) also get "presence": the names of the others who have the board open. Viewers of a
 * share link are anonymous and never see names.
 */
@Component
public class BoardEvents {

    /** Who has a board open; null for a share link. */
    record Viewer(int userId, String name) {
    }

    private record Listener(SseEmitter emitter, Viewer viewer) {
    }

    private final Map<Integer, Set<Listener>> listeners = new ConcurrentHashMap<>();

    /** A stream for a share link's viewer, open until they leave or the link stops working. */
    SseEmitter subscribe(int boardId) {
        return subscribe(boardId, null);
    }

    /** A stream for the owner or a member, open until they leave or lose access. */
    SseEmitter subscribe(int boardId, Viewer viewer) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout: dead connections fail the heartbeat
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
        send(emitter, SseEmitter.event().comment("open"));
        if (viewer != null) {
            sendPresence(boardId);
        }
        return emitter;
    }

    // After the commit, so the reload sees the change. Also for changes made outside a transaction.
    @TransactionalEventListener(fallbackExecution = true)
    void onChange(BoardChanged event) {
        for (Listener listener : listenersOf(event.boardId(), l -> true)) {
            // Browsers drop events without data.
            send(listener.emitter(), SseEmitter.event().name("changed").data("changed"));
        }
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
        ending.forEach(l -> l.emitter().complete());
        if (ending.stream().anyMatch(l -> l.viewer() != null)) {
            sendPresence(boardId);
        }
    }

    // nginx closes a proxied connection after 60 s without data.
    @Scheduled(fixedRateString = "PT30S")
    void heartbeat() {
        listeners.keySet().forEach(boardId -> listenersOf(boardId, l -> true)
                .forEach(l -> send(l.emitter(), SseEmitter.event().comment("ping"))));
    }

    // Each logged-in viewer gets the others, once per person (several tabs count once), in the
    // order they came.
    private void sendPresence(int boardId) {
        List<Listener> loggedIn = listenersOf(boardId, l -> l.viewer() != null);
        Map<Integer, String> people = new LinkedHashMap<>();
        loggedIn.forEach(l -> people.putIfAbsent(l.viewer().userId(), l.viewer().name()));
        for (Listener listener : loggedIn) {
            List<String> others = people.entrySet().stream()
                    .filter(person -> person.getKey() != listener.viewer().userId())
                    .map(person -> person.getValue() == null ? "Someone" : person.getValue())
                    .toList();
            send(listener.emitter(), SseEmitter.event().name("presence").data(others, MediaType.APPLICATION_JSON));
        }
    }

    private List<Listener> listenersOf(int boardId, Predicate<Listener> filter) {
        return listeners.getOrDefault(boardId, Set.of()).stream().filter(filter).toList();
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException gone) {
            // The client left (or the stream already ended); its callbacks remove it.
            emitter.completeWithError(gone);
        }
    }
}
