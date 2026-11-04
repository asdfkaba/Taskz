package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleDto;
import com.example.demo.board.BoardDtos.RuleRequest;
import com.example.demo.task.Checklists;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The automations of a board (see {@link BoardRule}; run by RuleEngine). Only the owner sees and
 * changes them; for others the board's rules are "not found".
 */
@Service
public class BoardRuleService {

    static final int MAX_ITEMS = 20;
    static final int MAX_DAYS = 365;

    private final BoardService boardService;
    private final BoardRuleRepository ruleRepository;

    public BoardRuleService(BoardService boardService, BoardRuleRepository ruleRepository) {
        this.boardService = boardService;
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public List<RuleDto> rulesOf(int boardId, String email) {
        Board board = boardService.findOwnBoard(boardId, email);
        return ruleRepository.findByColumnBoardOrderById(board).stream().map(RuleDto::from).toList();
    }

    @Transactional
    public RuleDto addRule(int boardId, String email, RuleRequest request) {
        Board board = boardService.findOwnBoard(boardId, email);
        BoardColumn column = request.columnId() == null ? null : boardService.findColumn(request.columnId(), email);
        if (column == null || !column.getBoard().getId().equals(board.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "columnId must be a column of this board");
        }
        BoardRule.Action action = validAction(request.action());
        BoardRule rule = new BoardRule(column, action, validPayload(action, request).toJson());
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        return RuleDto.from(ruleRepository.save(rule));
    }

    /** Changes the settings and whether the rule is on. Column and action stay; absent enabled too. */
    @Transactional
    public RuleDto updateRule(int ruleId, String email, RuleRequest request) {
        BoardRule rule = findRule(ruleId, email);
        BoardRule.Action action = BoardRule.Action.valueOf(rule.getAction());
        rule.setPayload(validPayload(action, request).toJson());
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        return RuleDto.from(rule);
    }

    @Transactional
    public void deleteRule(int ruleId, String email) {
        ruleRepository.delete(findRule(ruleId, email));
    }

    private BoardRule findRule(int ruleId, String email) {
        return ruleRepository.findById(ruleId)
                .filter(rule -> boardService.isOwner(rule.getColumn().getBoard(), email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
    }

    private static BoardRule.Action validAction(String action) {
        try {
            return BoardRule.Action.valueOf(String.valueOf(action));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action " + action);
        }
    }

    private static RulePayload validPayload(BoardRule.Action action, RuleRequest request) {
        return switch (action) {
            case APPEND_CHECKLIST, REPLACE_CHECKLIST -> new RulePayload(validItems(request.items()), null);
            case SET_DUE_IN -> {
                if (request.days() == null || request.days() < 0 || request.days() > MAX_DAYS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between 0 and " + MAX_DAYS);
                }
                yield new RulePayload(null, request.days());
            }
        };
    }

    // Cleaned like the items of a task (Checklists.clean), without blanks and duplicates.
    private static List<String> validItems(List<String> items) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String item : items == null ? List.<String>of() : items) {
            String text = Checklists.clean(item);
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        if (result.isEmpty() || result.size() > MAX_ITEMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A checklist needs 1 to " + MAX_ITEMS + " items");
        }
        return List.copyOf(result);
    }
}
