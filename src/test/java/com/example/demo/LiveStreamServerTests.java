package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardColumnRepository;
import com.example.demo.board.BoardMemberService;
import com.example.demo.board.BoardService;
import com.example.demo.task.Task;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live streams through the real server (Tomcat), which MockMvc skips: a viewer whose browser is
 * gone must not fail the requests of others. In production that was a 500 for someone opening the
 * board, as Tomcat refuses ending a broken stream from another thread.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRsaKeys.class)
class LiveStreamServerTests {

    @LocalServerPort
    int port;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardService boardService;
    @Autowired
    BoardMemberService memberService;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    TokenService tokenService;

    /** A live stream read like a browser does, over a plain socket so it can be cut off hard. */
    class Stream implements Closeable {
        final Socket socket;
        final BufferedReader in;
        final String status;

        Stream(int boardId, String token) throws IOException {
            socket = new Socket("localhost", port);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /boards/" + boardId + "/events HTTP/1.1\r\nHost: localhost\r\n"
                    + "Accept: text/event-stream\r\nCookie: access_token=" + token + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            status = in.readLine();
        }

        /**
         * Reads until a line contains the text; fails after the time is up. The response is
         * chunked, so "data:" and its value can be on separate lines.
         */
        void awaitLine(String text, Duration within) throws IOException {
            Instant end = Instant.now().plus(within);
            List<String> seen = new ArrayList<>();
            while (Instant.now().isBefore(end)) {
                socket.setSoTimeout((int) Math.max(1, Duration.between(Instant.now(), end).toMillis()));
                try {
                    String line = in.readLine();
                    if (line == null) {
                        break;
                    }
                    seen.add(line);
                    if (line.contains(text)) {
                        return;
                    }
                } catch (SocketTimeoutException timeUp) {
                    break;
                }
            }
            throw new AssertionError("No line with " + text + " in " + seen);
        }

        // Like a browser killed or a lost network: the server only notices when it writes.
        void cutOff() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private int renameTask(String token, int taskId, String title) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/tasks/" + taskId))
                .header("Content-Type", "application/json")
                .header("Cookie", "access_token=" + token)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"title\":\"" + title + "\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // Each open stream used to hold a database connection (open-session-in-view) for as long as
    // the tab was open; more tabs than the pool has connections stalled every other request.
    @Test
    void openStreamsDontUseUpTheDatabaseConnections() throws Exception {
        User carol = userRepository.save(new User("Carol", "carol@live.example"));
        Board board = boardService.createDefaultBoard(carol);
        String token = tokenService.generateToken(carol);

        List<Stream> tabs = new ArrayList<>();
        try {
            for (int i = 0; i < 15; i++) { // the pool has 10
                Stream tab = new Stream(board.getId(), token);
                tabs.add(tab);
                assertThat(tab.status).as("tab " + i).contains("200");
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/boards/" + board.getId()))
                    .header("Cookie", "access_token=" + token)
                    .timeout(Duration.ofSeconds(5))
                    .build();
            assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        } finally {
            for (Stream tab : tabs) {
                tab.close();
            }
        }
    }

    @Test
    void aViewerWhoseBrowserIsGoneDoesntFailOthers() throws Exception {
        User alice = userRepository.save(new User("Alice", "alice@live.example"));
        User bob = userRepository.save(new User("Bob", "bob@live.example"));
        Board board = boardService.createDefaultBoard(alice);
        String invite = memberService.createInvite(board.getId(), alice.getEmail()).path().substring("/join/".length());
        memberService.join(invite, bob.getEmail());
        Task task = new Task();
        task.setTitle("Live");
        task.setUser(alice);
        task.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        taskRepository.save(task);
        String aliceToken = tokenService.generateToken(alice);
        String bobToken = tokenService.generateToken(bob);

        List<Stream> tabs = new ArrayList<>();
        try (Stream aliceView = new Stream(board.getId(), aliceToken)) {
            assertThat(aliceView.status).contains("200");
            Stream bobView = new Stream(board.getId(), bobToken);
            aliceView.awaitLine("[\"Bob\"]", Duration.ofSeconds(5));
            bobView.cutOff();

            // Changes and new tabs write to Bob's dead stream until Tomcat notices; none may fail.
            Instant end = Instant.now().plusSeconds(10);
            boolean bobGone = false;
            for (int round = 0; !bobGone && Instant.now().isBefore(end); round++) {
                assertThat(renameTask(aliceToken, task.getId(), "Round " + round)).isEqualTo(200);
                Stream tab = new Stream(board.getId(), aliceToken);
                tabs.add(tab);
                assertThat(tab.status).as("opening the board in another tab").contains("200");
                try {
                    aliceView.awaitLine("[]", Duration.ofMillis(300)); // nobody else here
                    bobGone = true;
                } catch (AssertionError notYet) {
                    // Bob still listed: try again
                }
            }
            assertThat(bobGone).as("Bob shown as gone").isTrue();
        } finally {
            for (Stream tab : tabs) {
                tab.close();
            }
        }
    }
}
