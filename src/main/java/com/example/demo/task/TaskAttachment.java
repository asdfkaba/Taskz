package com.example.demo.task;

import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** A file attached to a task (table task_attachment, see V6__task_attachments.sql). */
@Entity
@Table(name = "task_attachment", indexes = @Index(name = "task_attachment_task_id", columnList = "task_id"))
@Getter
@NoArgsConstructor
public class TaskAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // The database deletes attachments together with their task; the files follow in AttachmentStorage.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Task task;

    // Who uploaded it; null if the user is gone.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // As uploaded, cleaned up (see TaskAttachmentService); only shown and used for downloads.
    @Column(name = "file_name", nullable = false, columnDefinition = "text")
    private String fileName;

    // The detected type (FileTypes), never the one the browser sent.
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long size;

    // Name of the file on disk: a random UUID, unrelated to the uploaded name.
    @Column(name = "storage_key", nullable = false, unique = true, length = 36)
    private String storageKey;

    @Column(nullable = false)
    private Instant created;

    TaskAttachment(Task task, User user, String fileName, String contentType, long size, String storageKey) {
        this.task = task;
        this.user = user;
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.storageKey = storageKey;
        this.created = Instant.now();
    }
}
