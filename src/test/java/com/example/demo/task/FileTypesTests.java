package com.example.demo.task;

import com.example.demo.task.FileTypes.FileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypesTests {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] GIF = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] WEBP = "RIFF$\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};

    @TempDir
    Path dir;

    private Optional<FileType> detect(byte[] content, String name) throws IOException {
        Path file = Files.write(dir.resolve("upload"), content);
        return FileTypes.detect(file, name);
    }

    private Optional<FileType> detect(String text, String name) throws IOException {
        return detect(text.getBytes(StandardCharsets.UTF_8), name);
    }

    @Test
    void imagesAndPdfsAreRecognizedByTheirFirstBytes() throws IOException {
        assertThat(detect(PNG, "screenshot.png")).contains(FileType.PNG);
        assertThat(detect(JPEG, "photo.jpg")).contains(FileType.JPEG);
        assertThat(detect(JPEG, "photo.JPEG")).contains(FileType.JPEG);
        assertThat(detect(GIF, "anim.gif")).contains(FileType.GIF);
        assertThat(detect(WEBP, "pic.webp")).contains(FileType.WEBP);
        assertThat(detect(PDF, "CV 2026.pdf")).contains(FileType.PDF);
    }

    @Test
    void theExtensionHasToFitTheContent() throws IOException {
        assertThat(detect(PNG, "cv.pdf")).isEmpty();
        assertThat(detect(PDF, "cv.png")).isEmpty();
        assertThat(detect(PDF, "cv")).isEmpty();
        // An image is not text, even when named so.
        assertThat(detect(PNG, "notes.txt")).isEmpty();
    }

    @Test
    void textIsUtf8WithoutNulBytes() throws IOException {
        assertThat(detect("Hello, Grüße 👋\n", "notes.txt")).contains(FileType.TEXT);
        assertThat(detect("# Interview\n- [ ] Questions", "prep.md")).contains(FileType.MARKDOWN);
        assertThat(detect("company;status\nAcme;applied\n", "jobs.csv")).contains(FileType.CSV);
        assertThat(detect("﻿with BOM", "bom.txt")).contains(FileType.TEXT);
        assertThat(detect(new byte[]{'h', 'i', 0, 'x'}, "nul.txt")).isEmpty();
        // Latin-1 "ü" alone is not valid UTF-8.
        assertThat(detect(new byte[]{'G', 'r', (byte) 0xFC, 'n'}, "latin1.txt")).isEmpty();
    }

    @Test
    void otherFilesAreRefused() throws IOException {
        assertThat(detect(EXE, "setup.exe")).isEmpty();
        assertThat(detect(EXE, "setup.txt")).isEmpty();
        assertThat(detect("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>", "logo.svg")).isEmpty();
        assertThat(detect("<html><script>alert(1)</script></html>", "page.html")).isEmpty();
        assertThat(detect("PK\u0003\u0004", "letter.docx")).isEmpty();
    }
}
