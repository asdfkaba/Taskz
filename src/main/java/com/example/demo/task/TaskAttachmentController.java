package com.example.demo.task;

import com.example.demo.task.TaskAttachmentService.AttachmentDto;
import com.example.demo.task.TaskAttachmentService.Download;
import com.example.demo.task.TaskAttachmentService.UploadSettings;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

// Attachments need a login; they are not part of shared boards.
@RestController
public class TaskAttachmentController {

    private final TaskAttachmentService attachmentService;

    public TaskAttachmentController(TaskAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    // Size limit and accepted types, so the page can show them and check files before uploading.
    @GetMapping("/attachments/settings")
    public UploadSettings settings() {
        return attachmentService.uploadSettings();
    }

    // Newest first.
    @GetMapping("/tasks/{id}/attachments")
    public List<AttachmentDto> getAttachments(@PathVariable("id") int taskId) {
        return attachmentService.attachmentsOf(taskId, currentUserEmail());
    }

    @PostMapping(path = "/tasks/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentDto upload(@PathVariable("id") int taskId, @RequestParam("file") MultipartFile file) {
        return attachmentService.upload(taskId, currentUserEmail(), file);
    }

    /**
     * The file. Images, PDFs and text (TXT, MD) open in the browser; CSV is a download, as browsers
     * download it anyway. The sandbox policy keeps any script in a file from running on this site,
     * whatever it is; the PDF viewers of Chrome and Firefox work under it.
     */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<Resource> download(@PathVariable("id") String key) {
        Download download = attachmentService.download(key, currentUserEmail());
        TaskAttachment attachment = download.attachment();
        String type = attachment.getContentType();
        boolean shown = type.startsWith("image/") || type.equals("application/pdf")
                || type.startsWith("text/plain") || type.startsWith("text/markdown");
        ContentDisposition disposition = (shown ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(attachment.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .contentLength(attachment.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(new FileSystemResource(download.file()));
    }

    @DeleteMapping("/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String key) {
        attachmentService.delete(key, currentUserEmail());
    }

    // The reason goes into the body, so the page can say why an upload was refused.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : "Request failed";
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", reason));
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
