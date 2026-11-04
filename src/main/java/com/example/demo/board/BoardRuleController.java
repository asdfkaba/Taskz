package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleDto;
import com.example.demo.board.BoardDtos.RuleRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class BoardRuleController {

    private final BoardRuleService ruleService;

    public BoardRuleController(BoardRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping("/boards/{id}/rules")
    public List<RuleDto> rules(@PathVariable("id") int boardId) {
        return ruleService.rulesOf(boardId, currentUserEmail());
    }

    @PostMapping("/boards/{id}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDto addRule(@PathVariable("id") int boardId, @RequestBody RuleRequest request) {
        return ruleService.addRule(boardId, currentUserEmail(), request);
    }

    @PutMapping("/rules/{id}")
    public RuleDto updateRule(@PathVariable("id") int ruleId, @RequestBody RuleRequest request) {
        return ruleService.updateRule(ruleId, currentUserEmail(), request);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable("id") int ruleId) {
        ruleService.deleteRule(ruleId, currentUserEmail());
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
