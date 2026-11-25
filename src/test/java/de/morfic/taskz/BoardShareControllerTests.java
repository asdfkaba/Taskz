package de.morfic.taskz;

import de.morfic.taskz.auth.jwt.TokenService;
import de.morfic.taskz.board.Board;
import de.morfic.taskz.board.BoardChanged;
import de.morfic.taskz.board.BoardDtos.ShareOptions;
import de.morfic.taskz.board.BoardDtos.SharedBoard;
import de.morfic.taskz.task.TaskAttachment;
import de.morfic.taskz.task.TaskAttachmentService.Download;
import de.morfic.taskz.board.BoardEvents;
import de.morfic.taskz.board.BoardShareEnded;
import de.morfic.taskz.board.BoardShare;
import de.morfic.taskz.board.ShareLinks;
import de.morfic.taskz.board.BoardShareController;
import de.morfic.taskz.board.BoardShareService;
import de.morfic.taskz.config.WebSecurityConfig;
import de.morfic.taskz.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BoardShareController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class, BoardEvents.class})
class BoardShareControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BoardShareService shareService;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    TokenService tokenService;

    @Autowired
    ApplicationEventPublisher events;

    // Public like the board: "changed" for this board only, until its link stops working.
    @Test
    void sharedBoardStreamsItsChangesUntilTheLinkEnds() throws Exception {
        when(shareService.sharedBoardId("abc")).thenReturn(7);

        MvcResult stream = mockMvc.perform(get("/share/abc/events"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        events.publishEvent(new BoardChanged(8));
        events.publishEvent(new BoardChanged(7));
        events.publishEvent(new BoardShareEnded(7));
        events.publishEvent(new BoardChanged(7));

        assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(stream.getResponse().getContentAsString()).startsWith(":open").containsOnlyOnce("event:changed\ndata:changed\n");
    }

    @Test
    void unknownLinkHasNoStream() throws Exception {
        when(shareService.sharedBoardId("nope")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/share/nope/events")).andExpect(status().isNotFound());
    }

    @Test
    void sharedBoardIsReadableWithoutLogin() throws Exception {
        when(shareService.sharedBoard("abc")).thenReturn(new SharedBoard("My board", "my-board-abc", List.of(), List.of(), List.of(), 0, false, false, false));

        mockMvc.perform(get("/share/abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My board"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // Without login the app answers 403 (no authentication entry point is configured).
    @Test
    void managingLinksNeedsLogin() throws Exception {
        mockMvc.perform(get("/boards/1/share")).andExpect(status().isForbidden());
        mockMvc.perform(post("/boards/1/share")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/boards/1/share")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/boards/1/share").contentType("application/json").content("{}")).andExpect(status().isForbidden());

        verifyNoInteractions(shareService);
    }

    @Test
    void otherMethodsOnShareUrlsNeedLogin() throws Exception {
        mockMvc.perform(delete("/share/abc")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ALICE)
    void unsharedBoardAnswersNoContent() throws Exception {
        when(shareService.getShare(1, ALICE)).thenReturn(Optional.empty());

        mockMvc.perform(get("/boards/1/share")).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = ALICE)
    void createReturnsLinkPathWithSlugAndKey() throws Exception {
        Board board = new Board();
        board.setSlug("bewerbungen");
        BoardShare share = new BoardShare(board);
        when(shareService.createShare(1, ALICE)).thenReturn(share);

        mockMvc.perform(post("/boards/1/share"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value(ShareLinks.path("bewerbungen", share.getToken())))
                .andExpect(jsonPath("$.path").value(org.hamcrest.Matchers.matchesPattern("bewerbungen-[A-Za-z0-9_-]{22}")))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void sharedTaskDataIsReadableWithoutLogin() throws Exception {
        when(shareService.sharedNotes("abc", 3)).thenReturn(List.of());
        when(shareService.sharedHistory("abc", 3)).thenReturn(List.of());
        when(shareService.sharedAttachments("abc", 3)).thenReturn(List.of());

        mockMvc.perform(get("/share/abc/tasks/3/notes")).andExpect(status().isOk());
        mockMvc.perform(get("/share/abc/tasks/3/history")).andExpect(status().isOk());
        mockMvc.perform(get("/share/abc/tasks/3/attachments")).andExpect(status().isOk());
    }

    @Test
    void sharedFileHasTheSameSafetyHeaders(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("f"), "hi");
        TaskAttachment attachment = mock(TaskAttachment.class);
        when(attachment.getContentType()).thenReturn("text/plain");
        when(attachment.getFileName()).thenReturn("plan.txt");
        when(attachment.getSize()).thenReturn(2L);
        when(shareService.sharedDownload("abc", "key")).thenReturn(new Download(attachment, file));

        mockMvc.perform(get("/share/abc/attachments/key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @WithMockUser(username = ALICE)
    void patchChangesOptions() throws Exception {
        Board board = new Board();
        board.setSlug("b");
        BoardShare share = new BoardShare(board);
        share.setShowNotes(true);
        when(shareService.updateOptions(1, ALICE, new ShareOptions(null, true, null, null))).thenReturn(share);

        mockMvc.perform(patch("/boards/1/share").contentType("application/json").content("{\"showNotes\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showNotes").value(true))
                .andExpect(jsonPath("$.showAttachments").value(false));
    }
}
