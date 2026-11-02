package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardColumnRepository;
import com.example.demo.board.BoardService;
import com.example.demo.task.Task;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uploads through the real server (Tomcat), which MockMvc skips: the multipart limit comes from
 * attachments.max-file-size, and a too large file gets a 413 answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRsaKeys.class)
class AttachmentUploadLimitTests {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void attachments(DynamicPropertyRegistry registry) {
        registry.add("attachments.dir", () -> dir.toString());
    }

    @LocalServerPort
    int port;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardService boardService;
    @Autowired
    BoardColumnRepository columnRepository;
    @Autowired
    TaskRepository taskRepository;
    @Autowired
    TokenService tokenService;

    private HttpResponse<String> upload(String token, int taskId, String name, byte[] content) throws Exception {
        String boundary = "----taskz-test";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/tasks/" + taskId + "/attachments"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", "access_token=" + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void filesUpToTheLimitPassLargerOnesGet413() throws Exception {
        User user = userRepository.save(new User("Lena", "lena@example.com", null));
        Board board = boardService.createDefaultBoard(user);
        Task task = new Task();
        task.setTitle("Upload");
        task.setUser(user);
        task.setBoardColumn(columnRepository.findByBoardOrderByPosition(board).get(0));
        taskRepository.save(task);
        String token = tokenService.generateToken(user);

        // 2 MB: above Spring's default limit of 1 MB, below ours.
        byte[] text = new byte[2 * 1024 * 1024];
        Arrays.fill(text, (byte) 'x');
        HttpResponse<String> ok = upload(token, task.getId(), "big.txt", text);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(201);
        assertThat(ok.body()).contains("\"name\":\"big.txt\"");

        byte[] tooLarge = new byte[11 * 1024 * 1024];
        Arrays.fill(tooLarge, (byte) 'x');
        assertThat(upload(token, task.getId(), "huge.txt", tooLarge).statusCode()).isEqualTo(413);
    }
}
