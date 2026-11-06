package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardChanged;
import com.example.demo.board.BoardDtos.SharedBoard;
import com.example.demo.board.BoardEvents;
import com.example.demo.board.BoardShareEnded;
import com.example.demo.board.BoardShare;
import com.example.demo.board.ShareLinks;
import com.example.demo.board.BoardShareController;
import com.example.demo.board.BoardShareService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

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
        when(shareService.sharedBoard("abc")).thenReturn(new SharedBoard("My board", "my-board-abc", List.of(), List.of(), List.of()));

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
}
