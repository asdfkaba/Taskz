package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.task.TaskAttachment;
import com.example.demo.task.TaskAttachmentController;
import com.example.demo.task.TaskAttachmentService;
import com.example.demo.task.TaskAttachmentService.AttachmentDto;
import com.example.demo.task.TaskAttachmentService.Download;
import com.example.demo.task.TaskAttachmentService.UploadSettings;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskAttachmentController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
@WithMockUser(username = TaskAttachmentControllerTests.ALICE)
class TaskAttachmentControllerTests {

    static final String ALICE = "alice@example.com";
    static final String KEY = "0b7a0f1e-6c3d-4f5a-9e2b-7d1c8a4e5f60";
    static final String OTHER_KEY = "8e2d4c6b-1a3f-4b5d-8c7e-9f0a1b2c3d4e";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskAttachmentService attachmentService;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    TokenService tokenService;

    @TempDir
    Path dir;

    private static AttachmentDto dto(String name, String type) {
        return new AttachmentDto(KEY, name, type, 3, type.startsWith("image/"), "Alice", 1, Instant.parse("2026-11-02T10:00:00Z"));
    }

    private Download download(String name, String type) throws Exception {
        TaskAttachment attachment = mock(TaskAttachment.class);
        when(attachment.getFileName()).thenReturn(name);
        when(attachment.getContentType()).thenReturn(type);
        when(attachment.getSize()).thenReturn(3L);
        return new Download(attachment, Files.writeString(dir.resolve("file"), "abc"));
    }

    @Test
    void listAndUpload() throws Exception {
        when(attachmentService.attachmentsOf(1, ALICE)).thenReturn(List.of(dto("cv.pdf", "application/pdf")));
        mockMvc.perform(get("/tasks/1/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("cv.pdf"))
                .andExpect(jsonPath("$[0].image").value(false))
                .andExpect(jsonPath("$[0].created").value("2026-11-02T10:00:00Z"));

        MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-".getBytes());
        when(attachmentService.upload(eq(1), eq(ALICE), any())).thenReturn(dto("cv.pdf", "application/pdf"));
        mockMvc.perform(multipart("/tasks/1/attachments").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(KEY));
    }

    @Test
    void settingsTellTheLimitAndTypes() throws Exception {
        when(attachmentService.uploadSettings()).thenReturn(
                new UploadSettings(10L * 1024 * 1024, "PNG, PDF", List.of("png", "pdf")));
        mockMvc.perform(get("/attachments/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxFileSize").value(10485760))
                .andExpect(jsonPath("$.types").value("PNG, PDF"))
                .andExpect(jsonPath("$.extensions[1]").value("pdf"));
    }

    @Test
    void aRefusedUploadSaysWhy() throws Exception {
        when(attachmentService.upload(eq(1), eq(ALICE), any())).thenThrow(
                new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PNG files"));
        mockMvc.perform(multipart("/tasks/1/attachments").file(new MockMultipartFile("file", "x.exe", null, new byte[]{1})))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Only PNG files"));
    }

    @Test
    void pdfsOpenInTheBrowserWithASandbox() throws Exception {
        Download pdf = download("Lebenslauf März.pdf", "application/pdf");
        when(attachmentService.download(KEY, ALICE)).thenReturn(pdf);
        mockMvc.perform(get("/attachments/" + KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().string("abc"))
                // Browsers take filename*; filename is an ASCII stand-in for old clients (Spring 7).
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"Lebenslauf Maerz.pdf\"; filename*=UTF-8''Lebenslauf%20M%C3%A4rz.pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    @Test
    void textOpensInTheBrowserAndCsvIsADownload() throws Exception {
        Download md = download("notes.md", "text/markdown; charset=utf-8");
        when(attachmentService.download(KEY, ALICE)).thenReturn(md);
        mockMvc.perform(get("/attachments/" + KEY))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")));
        Download txt = download("notes.txt", "text/plain; charset=utf-8");
        when(attachmentService.download(KEY, ALICE)).thenReturn(txt);
        mockMvc.perform(get("/attachments/" + KEY))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")));

        Download csv = download("jobs.csv", "text/csv; charset=utf-8");
        when(attachmentService.download(KEY, ALICE)).thenReturn(csv);
        mockMvc.perform(get("/attachments/" + KEY))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    @Test
    void imagesAreShownInline() throws Exception {
        Download png = download("screen.png", "image/png");
        when(attachmentService.download(KEY, ALICE)).thenReturn(png);
        mockMvc.perform(get("/attachments/" + KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")));
    }

    @Test
    void deleteAndNotFound() throws Exception {
        mockMvc.perform(delete("/attachments/" + KEY)).andExpect(status().isNoContent());
        verify(attachmentService).delete(KEY, ALICE);

        when(attachmentService.download(OTHER_KEY, ALICE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        mockMvc.perform(get("/attachments/" + OTHER_KEY)).andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    void attachmentsNeedALogin() throws Exception {
        mockMvc.perform(get("/tasks/1/attachments")).andExpect(status().isForbidden());
        mockMvc.perform(get("/attachments/" + KEY)).andExpect(status().isForbidden());
        verifyNoInteractions(attachmentService);
    }
}
