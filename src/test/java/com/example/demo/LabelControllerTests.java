package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardLabel;
import com.example.demo.board.LabelController;
import com.example.demo.board.LabelService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LabelController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
@WithMockUser(username = LabelControllerTests.ALICE)
class LabelControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    LabelService labelService;

    @MockBean
    UserRepository userRepository;

    @MockBean
    TokenService tokenService;

    private static BoardLabel label(String name, String color) {
        BoardLabel label = new BoardLabel(name, color, new Board());
        label.setId(7);
        return label;
    }

    @Test
    void createReturns201WithTheLabel() throws Exception {
        when(labelService.addLabel(1, ALICE, "Urgent", "#ef4444")).thenReturn(label("Urgent", "#ef4444"));

        mockMvc.perform(post("/boards/1/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Urgent\",\"color\":\"#ef4444\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Urgent"))
                .andExpect(jsonPath("$.color").value("#ef4444"));
    }

    @Test
    void patchPassesMissingFieldsAsNull() throws Exception {
        when(labelService.updateLabel(7, ALICE, "Later", null)).thenReturn(label("Later", "#ef4444"));

        mockMvc.perform(patch("/labels/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Later\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Later"));
        verify(labelService).updateLabel(7, ALICE, "Later", null);
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/labels/7")).andExpect(status().isNoContent());
        verify(labelService).deleteLabel(7, ALICE);
    }

    @Test
    @WithAnonymousUser
    void anonymousCannotChangeLabels() throws Exception {
        mockMvc.perform(delete("/labels/7")).andExpect(status().isForbidden());
        verifyNoInteractions(labelService);
    }
}
