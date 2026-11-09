package com.example.demo.user;

import com.example.demo.auth.jwt.RefreshToken;
import com.example.demo.auth.jwt.RefreshTokenRepository;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.ApiTokenService;
import com.example.demo.board.ApiTokenService.TokenDto;
import com.example.demo.board.Board;
import com.example.demo.board.BoardDtos.BoardDetail;
import com.example.demo.board.BoardMemberRepository;
import com.example.demo.board.BoardMemberService;
import com.example.demo.board.BoardService;
import com.example.demo.task.TaskAttachmentService;
import com.example.demo.task.TaskAttachmentService.AttachmentDto;
import com.example.demo.task.TaskDto;
import com.example.demo.task.TaskNoteService;
import com.example.demo.task.TaskNoteService.NoteDto;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The account settings: the user's logins (sessions), a download of their data, and deleting the account. */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BoardService boardService;
    private final BoardMemberService memberService;
    private final BoardMemberRepository memberRepository;
    private final TaskNoteService noteService;
    private final TaskAttachmentService attachmentService;
    private final ApiTokenService apiTokenService;
    private final EntityManager em;

    public AccountService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                          BoardService boardService, BoardMemberService memberService,
                          BoardMemberRepository memberRepository, TaskNoteService noteService,
                          TaskAttachmentService attachmentService, ApiTokenService apiTokenService, EntityManager em) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.boardService = boardService;
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.noteService = noteService;
        this.attachmentService = attachmentService;
        this.apiTokenService = apiTokenService;
        this.em = em;
    }

    // A login on one device. current: the one this request comes from.
    public record SessionDto(Long id, String userAgent, String ipAddress, Instant issuedAt, Instant expiresAt,
                             boolean current) {
    }

    public record Profile(String name, String email) {
    }

    public record Membership(String boardName, String ownerName) {
    }

    // notes and attachments (metadata only, not the files) by task id.
    public record ExportedBoard(BoardDetail board, Map<Integer, List<NoteDto>> notes,
                                Map<Integer, List<AttachmentDto>> attachments) {
    }

    public record Export(Instant exported, Profile user, List<ExportedBoard> boards, List<Membership> memberships,
                         List<TokenDto> apiTokens, List<SessionDto> sessions) {
    }

    /** currentRefreshToken: the refresh_token cookie of the request, or null. */
    @Transactional(readOnly = true)
    public List<SessionDto> sessions(String email, String currentRefreshToken) {
        String current = currentRefreshToken == null ? null : TokenService.hash(currentRefreshToken);
        return refreshTokenRepository.findActive(email, Instant.now()).stream()
                .map(t -> toDto(t, current))
                .toList();
    }

    private static SessionDto toDto(RefreshToken t, String currentHash) {
        return new SessionDto(t.getId(), t.getUserAgent(), t.getIpAddress(), t.getIssuedAt(), t.getExpiresAt(),
                t.getToken().equals(currentHash));
    }

    /** The device is logged out once its access token runs out, within a minute. */
    @Transactional
    public void endSession(long id, String email) {
        if (refreshTokenRepository.deleteOwn(id, email) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
    }

    /** Ends every login of the user but the current one. */
    @Transactional
    public void endOtherSessions(String email, String currentRefreshToken) {
        refreshTokenRepository.deleteOthers(email, currentRefreshToken == null ? "" : TokenService.hash(currentRefreshToken));
    }

    @Transactional(readOnly = true)
    public Export export(String email, String currentRefreshToken) {
        User user = findUser(email);
        List<ExportedBoard> boards = boardService.boardsOf(email).stream().map(board -> {
            BoardDetail detail = boardService.getBoard(board.getId(), email);
            Map<Integer, List<NoteDto>> notes = new LinkedHashMap<>();
            Map<Integer, List<AttachmentDto>> attachments = new LinkedHashMap<>();
            for (TaskDto task : detail.tasks()) {
                notes.put(task.id(), noteService.notesOf(task.id(), email));
                attachments.put(task.id(), attachmentService.attachmentsOf(task.id(), email));
            }
            return new ExportedBoard(detail, notes, attachments);
        }).toList();
        List<Membership> memberships = memberRepository.boardsOf(email).stream()
                .map(b -> new Membership(b.getName(), b.getUser().getName()))
                .toList();
        return new Export(Instant.now(), new Profile(user.getName(), user.getEmail()), boards, memberships,
                apiTokenService.list(email), sessions(email, currentRefreshToken));
    }

    /**
     * Deletes the account: the user's own boards with everything on them (also for their members),
     * their memberships, logins, API tokens and notifications. What they wrote on other people's
     * boards (tasks, notes, attachments, history) stays, without an author.
     * confirmEmail must be the account's email, so a stray request can't delete it.
     */
    @Transactional
    public void delete(String email, String confirmEmail) {
        User user = findUser(email);
        if (!email.equalsIgnoreCase(confirmEmail == null ? "" : confirmEmail.strip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type your email address to confirm");
        }
        for (Board board : boardService.boardsOf(email)) {
            boardService.deleteBoard(board.getId(), email);
        }
        for (Board board : memberRepository.boardsOf(email)) {
            memberService.leave(board.getId(), email); // also ends the user's live stream of that board
        }
        for (String entity : List.of("Task", "TaskNote", "TaskAttachment", "TaskEvent")) {
            em.createQuery("update " + entity + " e set e.user = null where e.user = :user")
                    .setParameter("user", user).executeUpdate();
        }
        refreshTokenRepository.deleteOthers(email, "");
        // A bulk delete, so JPA doesn't cascade anything itself; the database drops the API tokens,
        // notifications and notification settings (ON DELETE CASCADE).
        em.createQuery("delete from User u where u.id = :id").setParameter("id", user.getId()).executeUpdate();
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
