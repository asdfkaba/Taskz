package com.example.demo.task;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * A file above the multipart limit (see AppConfig) is refused before any controller runs, so the
 * controller's own error handler doesn't see it. This gives it the same { message } body, with the
 * limit in it.
 */
@RestControllerAdvice
public class UploadTooLargeHandler {

    private final AttachmentProperties properties;

    public UploadTooLargeHandler(AttachmentProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", TaskAttachmentService.tooLargeMessage(properties)));
    }
}
