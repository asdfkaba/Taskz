package com.example.demo.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;

/**
 * Where attachments are stored and how large they may be (attachments.* properties). The defaults
 * work without any configuration on the server.
 *
 * @param dir             directory for the files, ~/tasks/attachments by default; created when needed
 * @param maxFileSize     largest single file
 * @param maxTotalPerUser all attachments of one user's tasks together
 */
@ConfigurationProperties("attachments")
public record AttachmentProperties(
        Path dir,
        @DefaultValue("10MB") DataSize maxFileSize,
        @DefaultValue("500MB") DataSize maxTotalPerUser) {

    public AttachmentProperties {
        if (dir == null) {
            dir = Path.of(System.getProperty("user.home"), "tasks", "attachments");
        }
    }
}
