package com.example.demo.board;

import com.example.demo.board.BoardDtos.RuleDto;
import com.example.demo.board.BoardDtos.RuleRequest;
import com.example.demo.task.Checklists;
import com.example.demo.task.TaskNoteService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The automations of a board (see {@link BoardRule}; run by RuleEngine). A rule that moves tasks goes
 * when its target column is deleted (BoardService.deleteColumn). Only the owner sees and
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
    private final BoardColumnRepository columnRepository;

    public BoardRuleService(BoardService boardService, BoardRuleRepository ruleRepository,
                            BoardMemberRepository memberRepository, BoardColumnRepository columnRepository) {
        this.boardService = boardService;
        this.ruleRepository = ruleRepository;
        this.memberRepository = memberRepository;
        this.columnRepository = columnRepository;
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
        BoardRule.Trigger trigger = validTrigger(request.trigger(), action);
        BoardRule rule = new BoardRule(column, trigger, action, validPayload(trigger, action, request, column).toJson());
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        return RuleDto.from(ruleRepository.save(rule));
    }

    /** Changes the settings and whether the rule is on. Column, trigger and action stay; absent enabled too. */
    @Transactional
    public RuleDto updateRule(int ruleId, String email, RuleRequest request) {
        BoardRule rule = findRule(ruleId, email);
        BoardRule.Action action = BoardRule.Action.valueOf(rule.getAction());
        rule.setPayload(validPayload(BoardRule.Trigger.valueOf(rule.getTrigger()), action, request, rule.getColumn()).toJson());
        // Changed or switched on again: it watches from now, without catching up on what it missed.
        rule.setCheckedAt(Instant.now());
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

    private static BoardRule.Trigger validTrigger(String trigger, BoardRule.Action action) {
        try {
            BoardRule.Trigger result = trigger == null ? BoardRule.Trigger.ENTERED_COLUMN : BoardRule.Trigger.valueOf(trigger);
            if (result.allows(action)) {
                return result;
            }
        } catch (IllegalArgumentException e) {
            // Unknown trigger: same answer as for one that doesn't go with the action.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trigger " + trigger + " cannot run " + action);
    }

    // column: the rule's own.
    private RulePayload validPayload(BoardRule.Trigger trigger, BoardRule.Action action, RuleRequest request, BoardColumn column) {
        Board board = column.getBoard();
        Integer days = trigger == BoardRule.Trigger.IN_COLUMN_FOR ? validDays(request.days(), 1) : null;
        return switch (action) {
            case APPEND_CHECKLIST, REPLACE_CHECKLIST -> new RulePayload(validItems(request.items()), null, null, null, null, null);
            case ADD_NOTE_REMINDER -> new RulePayload(null, TaskNoteService.validText(request.text()), validDays(request.days(), 0),
                    Boolean.TRUE.equals(request.removeOnLeave()) ? true : null, null, null);
            case NOTIFY -> {
                String text = request.text() == null ? "" : request.text().strip();
                if (text.isEmpty() || text.length() > MAX_NOTIFICATION_LENGTH) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "A notification needs 1 to " + MAX_NOTIFICATION_LENGTH + " characters");
                }
                yield new RulePayload(null, text, days, null, validRecipients(request.userIds(), board), null);
            }
            case ARCHIVE -> new RulePayload(null, null, days, null, null, null);
            case MOVE -> {
                BoardColumn target = request.targetColumnId() == null ? null
                        : columnRepository.findById(request.targetColumnId()).orElse(null);
                if (target == null || !target.getBoard().getId().equals(board.getId()) || target.getId().equals(column.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetColumnId must be another column of this board");
                }
                yield new RulePayload(null, null, days, null, null, target.getId());
            }
        };
    }

    private static int validDays(Integer days, int min) {
        if (days == null || days < min || days > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between " + min + " and " + MAX_DAYS);
        }
        return days;
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
