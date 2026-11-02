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
}
