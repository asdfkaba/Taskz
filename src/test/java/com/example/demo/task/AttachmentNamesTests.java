package com.example.demo.task;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentNamesTests {

    @Test
    void namesKeepOnlyTheLastPathSegmentWithoutControlCharacters() {
        assertThat(TaskAttachmentService.validName("C:\\Users\\me\\cv.pdf")).isEqualTo("cv.pdf");
        assertThat(TaskAttachmentService.validName("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(TaskAttachmentService.validName("a\u0000b\nc.txt")).isEqualTo("abc.txt");
        assertThat(TaskAttachmentService.validName(" Lebenslauf März.pdf ")).isEqualTo("Lebenslauf März.pdf");
    }

    @Test
    void emptyAndOverlongNamesAreRefused() {
        assertThatThrownBy(() -> TaskAttachmentService.validName(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> TaskAttachmentService.validName("folder/")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> TaskAttachmentService.validName("x".repeat(197) + ".txt"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void limitsInMessagesAreNeverRoundedDown() {
        assertThat(TaskAttachmentService.readable(10L * 1024 * 1024)).isEqualTo("10 MB");
        assertThat(TaskAttachmentService.readable(1536L * 1024)).isEqualTo("1536 KB");
        assertThat(TaskAttachmentService.readable(1000)).isEqualTo("1 KB");
    }
}
