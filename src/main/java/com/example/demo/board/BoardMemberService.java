package com.example.demo.board;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Members of a board. The owner creates invite links (single use, valid for a week) and sends them
 * any way they like; whoever opens one while logged in can join. Members can leave, the owner can
 * remove them. What members may do is decided in {@link BoardService#canAccess}.
 */
@Service
public class BoardMemberService {

    static final Duration INVITE_VALIDITY = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BoardService boardService;
    private final BoardMemberRepository memberRepository;
    private final BoardInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    public BoardMemberService(BoardService boardService, BoardMemberRepository memberRepository,
                              BoardInviteRepository inviteRepository, UserRepository userRepository,
                              ApplicationEventPublisher events) {
        this.boardService = boardService;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
        this.userRepository = userRepository;
        this.events = events;
    }

    /** path: the link after the host, e.g. "/join/AbC..."; shown once, only its hash is stored. */
    public record NewInvite(String path, Instant expires) {
    }

    /** What an invite link leads to. member: the user can open the board already. */
    public record InviteInfo(String boardName, String ownerName, boolean member) {
    }

    // The owner comes first; joined is null for them.
    public record MemberDto(Integer userId, String name, boolean owner, Instant joined) {
    }

    @Transactional
    public NewInvite createInvite(int boardId, String email) {
        Board board = boardService.findOwnBoard(boardId, email);
        Instant now = Instant.now();
        inviteRepository.deleteExpired(now);
        byte[] key = new byte[16]; // 128 random bits
        RANDOM.nextBytes(key);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        BoardInvite invite = inviteRepository.save(new BoardInvite(board, TokenService.hash(token), now, now.plus(INVITE_VALIDITY)));
        return new NewInvite("/join/" + token, invite.getExpires());
    }

    /** Unknown, used and expired links all answer 404. */
    @Transactional(readOnly = true)
    public InviteInfo invite(String token, String email) {
        Board board = findInvite(token).getBoard();
        return new InviteInfo(board.getName(), board.getUser().getName(), boardService.canAccess(board, email));
    }

    /**
     * Joins the board and uses up the link. Someone who can open the board already (the owner,
     * a member) only gets its id, and the link stays usable for the one it was meant for.
     */
    @Transactional
    public int join(String token, String email) {
        BoardInvite invite = findInvite(token);
        Board board = invite.getBoard();
        if (boardService.canAccess(board, email)) {
            return board.getId();
        }
        // Two people opening the same link at once: only the first one gets in.
        if (inviteRepository.consume(invite.getId()) == 0) {
            throw inviteNotFound();
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        memberRepository.save(new BoardMember(board, user));
        return board.getId();
    }

    @Transactional(readOnly = true)
    public List<MemberDto> members(int boardId, String email) {
        Board board = boardService.findBoard(boardId, email);
        List<MemberDto> members = new ArrayList<>();
        members.add(new MemberDto(board.getUser().getId(), board.getUser().getName(), true, null));
        for (BoardMember member : memberRepository.findByBoardOrderByJoinedAscIdAsc(board)) {
            members.add(new MemberDto(member.getUser().getId(), member.getUser().getName(), false, member.getJoined()));
        }
        return members;
    }

    @Transactional
    public void removeMember(int boardId, int userId, String email) {
        Board board = boardService.findOwnBoard(boardId, email);
        if (memberRepository.deleteMember(board, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        }
        events.publishEvent(new BoardAccessEnded(boardId, userId));
    }

    @Transactional
    public void leave(int boardId, String email) {
        Board board = boardService.findBoard(boardId, email);
        if (boardService.isOwner(board, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The owner can't leave their board; delete it instead");
        }
        memberRepository.deleteMember(board, email);
        userRepository.findByEmail(email).ifPresent(user -> events.publishEvent(new BoardAccessEnded(boardId, user.getId())));
    }

    private BoardInvite findInvite(String token) {
        if (token == null || token.length() > 100) {
            throw inviteNotFound();
        }
        return inviteRepository.findValid(TokenService.hash(token), Instant.now()).orElseThrow(BoardMemberService::inviteNotFound);
    }

    private static ResponseStatusException inviteNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "This invite link doesn't work anymore");
    }
}
