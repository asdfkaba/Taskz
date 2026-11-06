package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleDto;
import com.example.demo.board.BoardDtos.RuleRequest;
import com.example.demo.task.Checklists;
import com.example.demo.task.TaskNoteService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The automations of a board (see {@link BoardRule}; run by RuleEngine). Only the owner sees and
 * changes them; for others the board's rules are "not found".
 */
@Service
public class BoardRuleService {

    static final int MAX_ITEMS = 20;
    static final int MAX_DAYS = 365;
    // Like Notification.MAX_TEXT_LENGTH.
    static final int MAX_NOTIFICATION_LENGTH = 500;

    private final BoardService boardService;
    private final BoardRuleRepository ruleRepository;
    private final BoardMemberRepository memberRepository;

    public BoardRuleService(BoardService boardService, BoardRuleRepository ruleRepository,
                            BoardMemberRepository memberRepository) {
        this.boardService = boardService;
        this.ruleRepository = ruleRepository;
        this.memberRepository = memberRepository;
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
        BoardRule rule = new BoardRule(column, action, validPayload(action, request, board).toJson());
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
        rule.setPayload(validPayload(action, request, rule.getColumn().getBoard()).toJson());
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

    private RulePayload validPayload(BoardRule.Action action, RuleRequest request, Board board) {
        return switch (action) {
            case APPEND_CHECKLIST, REPLACE_CHECKLIST -> new RulePayload(validItems(request.items()), null, null, null, null);
            case ADD_NOTE_REMINDER -> {
                if (request.days() == null || request.days() < 0 || request.days() > MAX_DAYS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between 0 and " + MAX_DAYS);
                }
                yield new RulePayload(null, TaskNoteService.validText(request.text()), request.days(),
                        Boolean.TRUE.equals(request.removeOnLeave()) ? true : null, null);
            }
            case NOTIFY -> {
                String text = request.text() == null ? "" : request.text().strip();
                if (text.isEmpty() || text.length() > MAX_NOTIFICATION_LENGTH) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "A notification needs 1 to " + MAX_NOTIFICATION_LENGTH + " characters");
                }
                yield new RulePayload(null, text, null, null, validRecipients(request.userIds(), board));
            }
        };
    }

    // The owner and the members of the board; each at most once, in the order given.
    private List<Integer> validRecipients(List<Integer> userIds, Board board) {
        Set<Integer> people = new HashSet<>();
        people.add(board.getUser().getId());
        memberRepository.findByBoardOrderByJoinedAscIdAsc(board).forEach(m -> people.add(m.getUser().getId()));
        List<Integer> result = List.copyOf(new LinkedHashSet<>(userIds == null ? List.of() : userIds));
        if (result.isEmpty() || !people.containsAll(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notify needs people of this board");
        }
        return result;
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
