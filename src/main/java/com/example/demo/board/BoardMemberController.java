package com.example.demo.board;

import com.example.demo.board.BoardDtos.BoardSummary;
import com.example.demo.board.BoardMemberService.InviteInfo;
import com.example.demo.board.BoardMemberService.MemberDto;
import com.example.demo.board.BoardMemberService.NewInvite;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class BoardMemberController {

    private final BoardMemberService memberService;
    private final BoardService boardService;

    public BoardMemberController(BoardMemberService memberService, BoardService boardService) {
        this.memberService = memberService;
        this.boardService = boardService;
    }

    @GetMapping("/boards/{id}/members")
    public List<MemberDto> members(@PathVariable("id") int boardId) {
        return memberService.members(boardId, currentUserEmail());
    }

    @DeleteMapping("/boards/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable("id") int boardId, @PathVariable("userId") int userId) {
        memberService.removeMember(boardId, userId, currentUserEmail());
    }

    // Leaving: a member takes themselves off the board.
    @DeleteMapping("/boards/{id}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable("id") int boardId) {
        memberService.leave(boardId, currentUserEmail());
    }

    @PostMapping("/boards/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public NewInvite createInvite(@PathVariable("id") int boardId) {
        return memberService.createInvite(boardId, currentUserEmail());
    }

    // Both need a login: whoever opens a link joins as themselves.
    @GetMapping("/invites/{token}")
    public InviteInfo invite(@PathVariable("token") String token) {
        return memberService.invite(token, currentUserEmail());
    }

    @PostMapping("/invites/{token}")
    public BoardSummary join(@PathVariable("token") String token) {
        int boardId = memberService.join(token, currentUserEmail());
        return boardService.summaryOf(boardId, currentUserEmail());
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
