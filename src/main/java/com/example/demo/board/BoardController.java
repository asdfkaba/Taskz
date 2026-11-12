package com.example.demo.board;

import com.example.demo.board.BoardDtos.BoardDetail;
import com.example.demo.board.BoardDtos.BoardSummary;
import com.example.demo.board.BoardDtos.ColumnDto;
import com.example.demo.board.BoardDtos.ColumnPatch;
import com.example.demo.board.BoardDtos.DueTask;
import com.example.demo.board.BoardDtos.NewBoardRequest;
import com.example.demo.board.BoardDtos.NewColumnRequest;
import com.example.demo.board.BoardDtos.RenameBoardRequest;
import com.example.demo.board.BoardDtos.TaskTemplate;
import tools.jackson.databind.JsonNode;
import com.example.demo.task.TaskDto;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class BoardController {

    private final BoardService boardService;
    private final BoardEvents boardEvents;
    private final UserRepository userRepository;

    public BoardController(BoardService boardService, BoardEvents boardEvents, UserRepository userRepository) {
        this.boardService = boardService;
        this.boardEvents = boardEvents;
        this.userRepository = userRepository;
    }

    @GetMapping("/boards")
    public List<BoardSummary> getBoards() {
        return boardService.summariesOf(currentUserEmail());
    }

    @GetMapping("/due")
    public List<DueTask> getDueTasks() {
        return boardService.dueTasksOf(currentUserEmail());
    }

    @PostMapping("/boards")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardSummary createBoard(@RequestBody NewBoardRequest request) {
        Board board = boardService.createBoard(currentUserEmail(), request.name());
        return new BoardSummary(board.getId(), board.getName(), board.getSlug(), true, null, null);
    }

    @PatchMapping("/boards/{id}")
    public BoardSummary renameBoard(@PathVariable("id") int id, @RequestBody RenameBoardRequest request) {
        boardService.renameBoard(id, currentUserEmail(), request.name());
        return boardService.summaryOf(id, currentUserEmail());
    }

    @DeleteMapping("/boards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBoard(@PathVariable("id") int id) {
        boardService.deleteBoard(id, currentUserEmail());
    }

    @GetMapping("/boards/{id}")
    public BoardDetail getBoard(@PathVariable("id") int id) {
        return boardService.getBoard(id, currentUserEmail());
    }

    // Archived tasks of the board, last archived first.
    @GetMapping("/boards/{id}/archive")
    public List<TaskDto> getArchive(@PathVariable("id") int id) {
        return boardService.archivedOf(id, currentUserEmail());
    }

    // Live view for the owner and members: "changed" after every change, "presence" with who else
    // has the board open. Closed when the user loses access.
    @GetMapping("/boards/{id}/events")
    public SseEmitter boardEvents(@PathVariable("id") int id, HttpServletResponse response) {
        String email = currentUserEmail();
        Board board = boardService.findBoard(id, email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        response.setHeader("X-Accel-Buffering", "no"); // nginx would hold the events back
        return boardEvents.subscribe(board.getId(), new BoardEvents.Viewer(user.getId(), user.getName()));
    }

    @PutMapping("/boards/{id}/task-template")
    public TaskTemplate setTaskTemplate(@PathVariable("id") int id, @RequestBody TaskTemplate request) {
        return new TaskTemplate(boardService.setTaskTemplate(id, currentUserEmail(), request.template()));
    }

    @PostMapping("/boards/{id}/columns")
    @ResponseStatus(HttpStatus.CREATED)
    public ColumnDto addColumn(@PathVariable("id") int boardId, @RequestBody NewColumnRequest request) {
        return ColumnDto.from(boardService.addColumn(boardId, currentUserEmail(), request.name(), request.color()));
    }

    // Read as a JSON object so that "color": null (remove the color) differs from no "color" at all.
    @PatchMapping("/columns/{id}")
    public ColumnDto updateColumn(@PathVariable("id") int columnId, @RequestBody JsonNode body) {
        return ColumnDto.from(boardService.updateColumn(columnId, currentUserEmail(), toColumnPatch(body)));
    }

    @DeleteMapping("/columns/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteColumn(@PathVariable("id") int columnId,
                             @RequestParam(name = "moveTo", required = false) Integer moveTo,
                             @RequestParam(name = "deleteTasks", defaultValue = "false") boolean deleteTasks) {
        boardService.deleteColumn(columnId, currentUserEmail(), moveTo, deleteTasks);
    }

    private static ColumnPatch toColumnPatch(JsonNode body) {
        if (!body.isObject()) {
            throw badRequest("Body must be a JSON object");
        }
        JsonNode name = body.get("name");
        if (name != null && !name.isString()) {
            throw badRequest("name must be a string");
        }
        JsonNode color = body.get("color");
        if (color != null && !color.isString() && !color.isNull()) {
            throw badRequest("color must be a string or null");
        }
        JsonNode position = body.get("position");
        if (position != null && !position.isInt()) {
            throw badRequest("position must be an integer");
        }
        return new ColumnPatch(
                name == null ? null : name.asString(),
                color != null,
                color == null || color.isNull() ? null : color.asString(),
                position == null ? null : position.asInt());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
