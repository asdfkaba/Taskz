package com.example.demo.board;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open live views of boards (server-sent events), by board. A change only says "changed"; the
 * view loads the board again, so it can't miss or misapply a detail. Not tied to share links, so
 * members of a shared board could listen here the same way later.
 */
@Component
public class BoardEvents {

    private final Map<Integer, Set<SseEmitter>> listeners = new ConcurrentHashMap<>();

    /** A stream for the board, open until the client leaves or the board's share ends. */
    SseEmitter subscribe(int boardId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout: dead connections fail the heartbeat
        listeners.compute(boardId, (id, set) -> {
            Set<SseEmitter> emitters = set != null ? set : ConcurrentHashMap.newKeySet();
            emitters.add(emitter);
            return emitters;
        });
        Runnable remove = () -> listeners.computeIfPresent(boardId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        // Sent right away, so the browser sees the stream open (and reloads after a reconnect).
        send(emitter, SseEmitter.event().comment("open"));
        return emitter;
    }

    // After the commit, so the reload sees the change. Also for changes made outside a transaction.
    @TransactionalEventListener(fallbackExecution = true)
    void onChange(BoardChanged event) {
        for (SseEmitter emitter : emittersOf(event.boardId())) {
            // Browsers drop events without data.
            send(emitter, SseEmitter.event().name("changed").data("changed"));
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onShareEnded(BoardShareEnded event) {
        emittersOf(event.boardId()).forEach(SseEmitter::complete);
    }

    // nginx closes a proxied connection after 60 s without data.
    @Scheduled(fixedRateString = "PT30S")
    void heartbeat() {
        listeners.keySet().forEach(boardId -> emittersOf(boardId).forEach(e -> send(e, SseEmitter.event().comment("ping"))));
    }

    private List<SseEmitter> emittersOf(int boardId) {
        return List.copyOf(listeners.getOrDefault(boardId, Set.of()));
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
