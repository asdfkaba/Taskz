package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardController;
import com.example.demo.board.BoardDtos;
import com.example.demo.board.BoardDtos.ColumnPatch;
import com.example.demo.board.BoardEvents;
import com.example.demo.board.BoardService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BoardController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class, BoardEvents.class})
@WithMockUser(username = BoardControllerTests.ALICE)
class BoardControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    BoardService boardService;

    @MockBean
    UserRepository userRepository;

    @MockBean
    TokenService tokenService;

    @BeforeEach
    void setUp() {
        BoardColumn column = new BoardColumn("To do", null, 0, new Board());
        column.setId(10);
        when(boardService.updateColumn(anyInt(), anyString(), any())).thenReturn(column);
    }

    private void patchColumn(String json) throws Exception {
        mockMvc.perform(patch("/columns/10").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @Test
    void taskTemplateIsSetAndReturned() throws Exception {
        when(boardService.setTaskTemplate(5, ALICE, " - [ ] Step ")).thenReturn("- [ ] Step");
        mockMvc.perform(put("/boards/5/task-template").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\" - [ ] Step \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("- [ ] Step"));
    }

    @Test
    void missingFieldsStayUnchanged() throws Exception {
        patchColumn("{\"name\":\"Backlog\"}");

        verify(boardService).updateColumn(10, ALICE, new ColumnPatch("Backlog", false, null, null));
    }

    @Test
    void nullColorRemovesColor() throws Exception {
        patchColumn("{\"color\":null}");

        verify(boardService).updateColumn(10, ALICE, new ColumnPatch(null, true, null, null));
    }

    @Test
    void positionAndColorTogether() throws Exception {
        patchColumn("{\"color\":\"#3b82f6\",\"position\":2}");

        verify(boardService).updateColumn(10, ALICE, new ColumnPatch(null, true, "#3b82f6", 2));
    }

    @Test
    void wrongTypesAreRejected() throws Exception {
        mockMvc.perform(patch("/columns/10").contentType(MediaType.APPLICATION_JSON).content("{\"position\":\"first\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/columns/10").contentType(MediaType.APPLICATION_JSON).content("{\"name\":null}"))
                .andExpect(status().isBadRequest());

        verify(boardService, never()).updateColumn(anyInt(), anyString(), any());
    }

    @Test
    void createBoardReturnsSummary() throws Exception {
        Board created = new Board("Side project", null);
        created.setId(5);
        created.setSlug("side-project");
        when(boardService.createBoard(ALICE, "Side project")).thenReturn(created);

        mockMvc.perform(post("/boards").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Side project\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Side project"))
                .andExpect(jsonPath("$.slug").value("side-project"));
    }

    @Test
    void renameBoardReturnsSummaryWithNewSlug() throws Exception {
        Board renamed = new Board("Job hunt", null);
        renamed.setId(5);
        renamed.setSlug("job-hunt");
        when(boardService.renameBoard(5, ALICE, "Job hunt")).thenReturn(renamed);
        when(boardService.summaryOf(5, ALICE)).thenReturn(new BoardDtos.BoardSummary(5, "Job hunt", "job-hunt", true, null));

        mockMvc.perform(patch("/boards/5").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Job hunt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Job hunt"))
                .andExpect(jsonPath("$.slug").value("job-hunt"));
    }

    @Test
    void deleteBoard() throws Exception {
        mockMvc.perform(delete("/boards/5")).andExpect(status().isNoContent());

        verify(boardService).deleteBoard(5, ALICE);
    }

    @Test
    void deletePassesTaskChoice() throws Exception {
        mockMvc.perform(delete("/columns/10?moveTo=11")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/columns/10?deleteTasks=true")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/columns/10")).andExpect(status().isNoContent());

        verify(boardService).deleteColumn(10, ALICE, 11, false);
        verify(boardService).deleteColumn(10, ALICE, null, true);
        verify(boardService).deleteColumn(10, ALICE, null, false);
    }
}
