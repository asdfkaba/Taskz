package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.*;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Live streams of a board for its owner and members, next to those of share links. */
@WebMvcTest({BoardController.class, BoardShareController.class})
@Import({WebSecurityConfig.class, TestRsaKeys.class, BoardEvents.class})
class BoardEventsTests {

    static final String ALICE = "alice@example.com";
    static final String BOB = "bob@example.com";

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationEventPublisher events;
    @MockBean BoardService boardService;
    @MockBean BoardShareService shareService;
    @MockBean UserRepository userRepository;
    @MockBean TokenService tokenService;

    @BeforeEach
    void setUp() {
        Board board = new Board("My board", null);
        board.setId(7);
        when(boardService.findBoard(7, ALICE)).thenReturn(board);
        when(boardService.findBoard(7, BOB)).thenReturn(board);
        when(boardService.findBoard(8, BOB)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(shareService.sharedBoardId("link")).thenReturn(7);
        User alice = new User("Alice", ALICE);
        alice.setId(1);
        User bob = new User("Bob", BOB);
        bob.setId(2);
        when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice));
        when(userRepository.findByEmail(BOB)).thenReturn(Optional.of(bob));
    }

    private MvcResult open(String email) throws Exception {
        return mockMvc.perform(get("/boards/7/events").with(user(email)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String received(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    @Test
    void membersSeeWhoElseIsHereAndLoseTheStreamWithTheirAccess() throws Exception {
        MvcResult alice = open(ALICE);
        MvcResult guest = mockMvc.perform(get("/share/link/events")).andExpect(request().asyncStarted()).andReturn();
        MvcResult bob = open(BOB);
        open(BOB); // a second tab counts once

        assertThat(received(alice)).contains("event:presence\ndata:[]").endsWith("event:presence\ndata:[\"Bob\"]\n\n");
        assertThat(received(bob)).contains("event:presence\ndata:[\"Alice\"]");
        // Share links are anonymous: no names, and the link ending keeps members connected.
        assertThat(received(guest)).doesNotContain("presence");
        events.publishEvent(new BoardShareEnded(7));
        events.publishEvent(new BoardChanged(7));
        assertThat(received(bob)).contains("event:changed");
        assertThat(received(guest)).doesNotContain("event:changed");

        // Bob is removed: his streams end, and Alice sees him gone.
        events.publishEvent(new BoardAccessEnded(7, 2));
        events.publishEvent(new BoardChanged(7));
        assertThat(received(bob)).containsOnlyOnce("event:changed");
        assertThat(received(alice)).contains("event:presence\ndata:[]\n\nevent:changed");
    }

    @Test
    void boardsOfOthersHaveNoStream() throws Exception {
        mockMvc.perform(get("/boards/8/events").with(user(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(get("/boards/7/events")).andExpect(status().isForbidden());
    }
}
