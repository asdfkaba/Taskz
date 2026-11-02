package com.example.demo;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardService;
import com.example.demo.board.BoardLabel;
import com.example.demo.board.LabelService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.task.Task;
import com.example.demo.task.TaskController;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
@Import({WebSecurityConfig.class, TestRsaKeys.class})
@WithMockUser(username = TaskControllerTests.ALICE)
class TaskControllerTests {

    static final String ALICE = "alice@example.com";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TaskRepository taskRepository;

    @MockBean
    UserRepository userRepository;

    @MockBean
    TokenService tokenService;

    @MockBean
    BoardService boardService;

    @MockBean
    LabelService labelService;

    @MockBean
    TaskHistoryService historyService;

    private BoardColumn aliceColumn() {
        BoardColumn column = new BoardColumn("To do", null, 0, new Board("My board", new User("Alice", ALICE)));
        column.setId(10);
        return column;
    }

    private Task aliceTask() {
        User alice = new User("Alice", ALICE);
        Task task = new Task("Old title", LocalDateTime.of(2026, 10, 27, 12, 0), null, "Old description");
        task.setId(1);
        task.setCreated(Instant.parse("2026-10-01T09:00:00Z"));
        task.setUser(alice);
        task.setBoardColumn(aliceColumn());
        return task;
    }

    @Test
    void tickingACheckboxSavesAndRecordsIt() throws Exception {
        Task task = aliceTask();
        task.setDescription("- [ ] Cover letter\n- [ ] CV");
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/tasks/1/checkbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [ ] Cover letter\\n- [x] CV\",\"line\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("- [ ] Cover letter\n- [x] CV"));

        verify(historyService).recordCheckbox(task, "CV", true, List.of(), 0, ALICE);
        verify(historyService, never()).recordUpdate(any(), any(), any());
        verify(boardService).changed(task.getBoardColumn().getBoard());
    }

    // Older clients send no line; then exactly one checkbox may change.
    @Test
    void checkboxWithoutLineMustBeTheOnlyChange() throws Exception {
        Task task = aliceTask();
        task.setDescription("- [ ] Cover letter\n- [ ] CV");
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/tasks/1/checkbox").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] Cover letter\\n- [ ] CV\"}"))
                .andExpect(status().isOk());
        verify(historyService).recordCheckbox(task, "Cover letter", true, List.of(), 0, ALICE);

        task.setDescription("- [ ] Cover letter\n- [ ] CV");
        mockMvc.perform(patch("/tasks/1/checkbox").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] Cover letter\\n- [x] CV\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void tickingTheLastSubItemCompletesTheParent() throws Exception {
        Task task = aliceTask();
        task.setDescription("- [ ] Application\n  - [x] Cover letter\n  - [ ] CV");
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/tasks/1/checkbox").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] Application\\n  - [x] Cover letter\\n  - [x] CV\",\"line\":2}"))
                .andExpect(status().isOk());

        verify(historyService).recordCheckbox(task, "CV", true, List.of("Application"), 0, ALICE);
    }

    @Test
    void tickingAParentTicksItsSubItems() throws Exception {
        Task task = aliceTask();
        task.setDescription("- [ ] Application\n  - [x] Cover letter\n  - [ ] CV\n  - [ ] Portfolio");
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/tasks/1/checkbox").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] Application\\n  - [x] Cover letter\\n  - [x] CV\\n  - [x] Portfolio\",\"line\":0}"))
                .andExpect(status().isOk());

        verify(historyService).recordCheckbox(task, "Application", true, List.of(), 2, ALICE);
    }

    @Test
    void changesInDifferentDirectionsOrWithoutTheClickedLineAreRejected() throws Exception {
        Task task = aliceTask();
        task.setDescription("- [ ] Cover letter\n- [x] CV");
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));

        mockMvc.perform(patch("/tasks/1/checkbox").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] Cover letter\\n- [ ] CV\",\"line\":0}"))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/tasks/1/checkbox").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] Cover letter\\n- [x] CV\",\"line\":1}"))
                .andExpect(status().isConflict());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(historyService);
    }

    @Test
    void checkboxRequestWithOtherChangesIsRejected() throws Exception {
        Task task = aliceTask();
        task.setDescription("- [ ] Cover letter\n- [ ] CV");
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));

        // E.g. the description was edited in another tab in the meantime.
        mockMvc.perform(patch("/tasks/1/checkbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] Cover letter\\nCV\",\"line\":0}"))
                .andExpect(status().isConflict());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(historyService);
        assertThat(task.getDescription()).isEqualTo("- [ ] Cover letter\n- [ ] CV");
    }

    @Test
    void checkboxOfOtherUsersTaskIsNotFound() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.empty());
        mockMvc.perform(patch("/tasks/1/checkbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"- [x] CV\"}"))
                .andExpect(status().isNotFound());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void updateChangesOwnTaskAndKeepsCreated() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(aliceTask()));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"New title","description":"New description",
                                 "due_date":"2026-11-02T09:30:00","status":"DONE",
                                 "created":null,"columnId":99}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.columnId").value(10));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getDue_date()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 30));
        // PUT does not move tasks; that is what /move is for.
        assertThat(saved.getValue().getBoardColumn().getId()).isEqualTo(10);
        assertThat(saved.getValue().getCreated()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(saved.getValue().getUser().getEmail()).isEqualTo(ALICE);
    }

    // The frontend sends due_date back in the array format it received from GET.
    @Test
    void updateAcceptsDateInArrayFormat() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(aliceTask()));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"due_date\":[2026,11,4,10,0,0,0],\"status\":\"DONE\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getDue_date()).isEqualTo(LocalDateTime.of(2026, 11, 4, 10, 0));
    }

    @Test
    void updateAcceptsLongMarkdownDescription() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(aliceTask()));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        String description = "- [ ] item\n".repeat(900); // 9900 chars, far over the old 255

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"description\":" + jsonString(description) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(description));
    }

    @Test
    void updateRejectsTooLongDescription() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(aliceTask()));
        String description = "x".repeat(10_001);

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"description\":" + jsonString(description) + "}"))
                .andExpect(status().isBadRequest());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void titlesAreTrimmedAndMustFitTheColumn() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(aliceTask()));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        for (String bad : new String[]{"\"   \"", "null", jsonString("x".repeat(256))}) {
            mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        verify(taskRepository, never()).save(any());

        mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + jsonString("  " + "x".repeat(255) + "  ") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("x".repeat(255)));
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }

    @Test
    void cannotUpdateTaskOfOtherUser() throws Exception {
        when(taskRepository.findByIdAndUserEmail(2, ALICE)).thenReturn(Optional.empty());

        mockMvc.perform(put("/tasks/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());

        verify(taskRepository, never()).save(any());
    }

    @Test
    void cannotDeleteTaskOfOtherUser() throws Exception {
        when(taskRepository.findByIdAndUserEmail(2, ALICE)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/tasks/2/delete"))
                .andExpect(status().isNotFound());

        verify(taskRepository, never()).delete(any());
        verify(taskRepository, never()).deleteById(any());
    }

    @Test
    void deletesOwnTask() throws Exception {
        Task task = aliceTask();
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));

        mockMvc.perform(delete("/tasks/1/delete"))
                .andExpect(status().isOk());

        verify(taskRepository).delete(task);
        verify(boardService).changed(task.getBoardColumn().getBoard());
    }

    @Test
    void cannotReadTaskOfOtherUser() throws Exception {
        when(taskRepository.findByIdAndUserEmail(2, ALICE)).thenReturn(Optional.empty());

        mockMvc.perform(get("/tasks/2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createIgnoresIdFromBodySoPostCannotOverwrite() throws Exception {
        BoardColumn column = aliceColumn();
        when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(new User("Alice", ALICE)));
        when(boardService.findOwnColumn(10, ALICE)).thenReturn(column);
        when(taskRepository.countByBoardColumn(column)).thenReturn(2);

        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":2,\"title\":\"New task\",\"columnId\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.columnId").value(10))
                .andExpect(jsonPath("$.position").value(2));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getTitle()).isEqualTo("New task");
        assertThat(saved.getValue().getUser().getEmail()).isEqualTo(ALICE);
        verify(boardService).changed(column.getBoard());
    }

    @Test
    void createRequiresColumn() throws Exception {
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New task\"}"))
                .andExpect(status().isBadRequest());

        verify(taskRepository, never()).save(any());
    }

    @Test
    void cannotCreateTaskInColumnOfOtherUser() throws Exception {
        when(boardService.findOwnColumn(20, ALICE))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Column not found"));

        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New task\",\"columnId\":20}"))
                .andExpect(status().isNotFound());

        verify(taskRepository, never()).save(any());
    }

    @Test
    void moveDelegatesToBoardService() throws Exception {
        Task task = aliceTask();
        task.setPosition(3);
        when(boardService.moveTask(1, 10, 3, ALICE)).thenReturn(task);

        mockMvc.perform(patch("/tasks/1/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columnId\":10,\"position\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(3));
    }

    @Test
    void moveRequiresColumnAndPosition() throws Exception {
        mockMvc.perform(patch("/tasks/1/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columnId\":10}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(boardService);
    }

    @Test
    void updateWithLabelIdsSetsLabelsOfTheBoard() throws Exception {
        Task task = aliceTask();
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        BoardLabel urgent = new BoardLabel("Urgent", "#ef4444", task.getBoardColumn().getBoard());
        urgent.setId(7);
        when(labelService.labelsForTask(java.util.List.of(7), task.getBoardColumn().getBoard()))
                .thenReturn(new java.util.HashSet<>(java.util.Set.of(urgent)));

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"labelIds\":[7]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labelIds[0]").value(7));
    }

    @Test
    void updateWithoutLabelIdsKeepsLabels() throws Exception {
        Task task = aliceTask();
        BoardLabel urgent = new BoardLabel("Urgent", "#ef4444", task.getBoardColumn().getBoard());
        urgent.setId(7);
        task.getLabels().add(urgent);
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labelIds[0]").value(7));
        verifyNoInteractions(labelService);
    }

    @Test
    void foreignLabelIsRejected() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(aliceTask()));
        when(labelService.labelsForTask(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label 99 is not a label of this board"));

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"labelIds\":[99]}"))
                .andExpect(status().isBadRequest());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void updateRecordsHistory() throws Exception {
        when(taskRepository.findByIdAndUserEmail(1, ALICE)).thenReturn(Optional.of(aliceTask()));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New title\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<TaskHistoryService.Snapshot> before = ArgumentCaptor.forClass(TaskHistoryService.Snapshot.class);
        verify(historyService).recordUpdate(before.capture(), any(Task.class), eq(ALICE));
        assertThat(before.getValue().title()).isEqualTo("Old title");
        verify(boardService).changed(any());
    }

    @Test
    void historyOfOtherUsersTaskIsNotFound() throws Exception {
        when(taskRepository.findByIdAndUserEmail(5, ALICE)).thenReturn(Optional.empty());
        mockMvc.perform(get("/tasks/5/history")).andExpect(status().isNotFound());
        verifyNoInteractions(historyService);
    }
}
