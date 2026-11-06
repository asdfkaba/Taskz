package com.example.demo;

import com.example.demo.task.RuleEngine;
import com.example.demo.task.ChecklistItem;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardService;
import com.example.demo.board.BoardLabel;
import com.example.demo.board.LabelService;
import com.example.demo.config.WebSecurityConfig;
import com.example.demo.task.Task;
import com.example.demo.task.TaskController;
import com.example.demo.task.TaskDto;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.task.TaskRepository;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
    RuleEngine ruleEngine;

    @MockitoBean
    TaskRepository taskRepository;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    TokenService tokenService;

    @MockitoBean
    BoardService boardService;

    @MockitoBean
    LabelService labelService;

    @MockitoBean
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

    private static ChecklistItem item(Task task, int id, String text, boolean checked) {
        ChecklistItem item = new ChecklistItem();
        item.setId(id);
        item.setTask(task);
        item.setText(text);
        item.setChecked(checked);
        task.getChecklist().add(item);
        return item;
    }

    @Test
    void tickingAnItemSavesAndRecordsItWithoutChangingTheRevision() throws Exception {
        Task task = aliceTask();
        item(task, 5, "Cover letter", false);
        String revision = TaskDto.revisionOf(task);
        when(boardService.findTask(1, ALICE)).thenReturn(task);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/tasks/1/checklist/5").contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checklist[0].checked").value(true))
                .andExpect(jsonPath("$.revision").value(revision));

        verify(historyService).recordCheckbox(task, "Cover letter", true, ALICE);
        verify(boardService).changed(task.getBoardColumn().getBoard());
    }

    @Test
    void tickingAnUnknownItemIsNotFound() throws Exception {
        Task task = aliceTask();
        item(task, 5, "Cover letter", false);
        when(boardService.findTask(1, ALICE)).thenReturn(task);

        mockMvc.perform(patch("/tasks/1/checklist/6").contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/tasks/1/checklist/5").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savingReplacesTheChecklistKeepingTicksOfKnownItems() throws Exception {
        Task task = aliceTask();
        item(task, 5, "Cover letter", true);
        item(task, 6, "CV", false);
        when(boardService.findTask(1, ALICE)).thenReturn(task);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"checklist\":[{\"text\":\"- [ ] Photo\"},{\"id\":5,\"text\":\"Letter\"},{\"text\":\" \"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checklist.length()").value(2))
                .andExpect(jsonPath("$.checklist[0].text").value("Photo"))
                .andExpect(jsonPath("$.checklist[0].checked").value(false))
                .andExpect(jsonPath("$.checklist[1].id").value(5))
                .andExpect(jsonPath("$.checklist[1].text").value("Letter"))
                .andExpect(jsonPath("$.checklist[1].checked").value(true));
        assertThat(task.getChecklist()).extracting(ChecklistItem::getPosition).containsExactly(0, 1);
    }

    @Test
    void savingWithoutAChecklistKeepsItAndForeignItemsAreRejected() throws Exception {
        Task task = aliceTask();
        item(task, 5, "CV", false);
        when(boardService.findTask(1, ALICE)).thenReturn(task);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"T\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checklist[0].text").value("CV"));
        mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"checklist\":[{\"id\":99,\"text\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void updateChangesOwnTaskAndKeepsCreated() throws Exception {
        when(boardService.findTask(1, ALICE)).thenReturn(aliceTask());
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
        when(boardService.findTask(1, ALICE)).thenReturn(aliceTask());
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
        when(boardService.findTask(1, ALICE)).thenReturn(aliceTask());
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
        when(boardService.findTask(1, ALICE)).thenReturn(aliceTask());
        String description = "x".repeat(10_001);

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"description\":" + jsonString(description) + "}"))
                .andExpect(status().isBadRequest());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void titlesAreTrimmedAndMustFitTheColumn() throws Exception {
        when(boardService.findTask(1, ALICE)).thenReturn(aliceTask());
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
        when(boardService.findTask(2, ALICE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        mockMvc.perform(put("/tasks/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());

        verify(taskRepository, never()).save(any());
    }

    @Test
    void cannotDeleteTaskOfOtherUser() throws Exception {
        when(boardService.findTask(2, ALICE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        mockMvc.perform(delete("/tasks/2/delete"))
                .andExpect(status().isNotFound());

        verify(taskRepository, never()).delete(any());
        verify(taskRepository, never()).deleteById(any());
    }

    @Test
    void deletesOwnTask() throws Exception {
        Task task = aliceTask();
        when(boardService.findTask(1, ALICE)).thenReturn(task);

        mockMvc.perform(delete("/tasks/1/delete"))
                .andExpect(status().isOk());

        verify(taskRepository).delete(task);
        verify(boardService).changed(task.getBoardColumn().getBoard());
    }

    @Test
    void cannotReadTaskOfOtherUser() throws Exception {
        when(boardService.findTask(2, ALICE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        mockMvc.perform(get("/tasks/2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createIgnoresIdFromBodySoPostCannotOverwrite() throws Exception {
        BoardColumn column = aliceColumn();
        when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(new User("Alice", ALICE)));
        when(boardService.findColumn(10, ALICE)).thenReturn(column);
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
        when(boardService.findColumn(20, ALICE))
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
        when(boardService.findTask(1, ALICE)).thenReturn(task);
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
        when(boardService.findTask(1, ALICE)).thenReturn(task);
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
        when(boardService.findTask(1, ALICE)).thenReturn(aliceTask());
        when(labelService.labelsForTask(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label 99 is not a label of this board"));

        mockMvc.perform(put("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"labelIds\":[99]}"))
                .andExpect(status().isBadRequest());
        verify(taskRepository, never()).save(any());
    }

    // Saving from an older revision means someone else saved in between: refused, not overwritten.
    @Test
    void updateFromAnOlderRevisionIsAConflict() throws Exception {
        Task task = aliceTask();
        String current = TaskDto.revisionOf(task);
        when(boardService.findTask(1, ALICE)).thenReturn(task);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mine\",\"revision\":\"0000000000000000\"}"))
                .andExpect(status().isConflict());
        verify(taskRepository, never()).save(any());

        mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mine\",\"revision\":\"" + current + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(TaskDto.revisionOf(task)));
        assertThat(TaskDto.revisionOf(task)).isNotEqualTo(current);
    }

    // Moving and positions are not part of the revision: moving isn't a conflict for an edit.
    @Test
    void revisionOnlyCoversTheEditedFields() {
        Task task = aliceTask();
        String before = TaskDto.revisionOf(task);
        task.setPosition(5);
        task.setBoardColumn(aliceColumn());
        assertThat(TaskDto.revisionOf(task)).isEqualTo(before);
        task.setDescription("Changed");
        assertThat(TaskDto.revisionOf(task)).isNotEqualTo(before);
    }

    @Test
    void updateRecordsHistory() throws Exception {
        when(boardService.findTask(1, ALICE)).thenReturn(aliceTask());
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
        when(boardService.findTask(5, ALICE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        mockMvc.perform(get("/tasks/5/history")).andExpect(status().isNotFound());
        verifyNoInteractions(historyService);
    }
}
