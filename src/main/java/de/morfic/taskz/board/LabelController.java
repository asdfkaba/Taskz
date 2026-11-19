package de.morfic.taskz.board;

import de.morfic.taskz.board.BoardDtos.LabelDto;
import de.morfic.taskz.board.BoardDtos.LabelRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
public class LabelController {

    private final LabelService labelService;

    public LabelController(LabelService labelService) {
        this.labelService = labelService;
    }

    @PostMapping("/boards/{id}/labels")
    @ResponseStatus(HttpStatus.CREATED)
    public LabelDto addLabel(@PathVariable("id") int boardId, @RequestBody LabelRequest request) {
        return LabelDto.from(labelService.addLabel(boardId, currentUserEmail(), request.name(), request.color()));
    }

    // Absent fields stay unchanged.
    @PatchMapping("/labels/{id}")
    public LabelDto updateLabel(@PathVariable("id") int labelId, @RequestBody LabelRequest request) {
        return LabelDto.from(labelService.updateLabel(labelId, currentUserEmail(), request.name(), request.color()));
    }

    @DeleteMapping("/labels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLabel(@PathVariable("id") int labelId) {
        labelService.deleteLabel(labelId, currentUserEmail());
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
