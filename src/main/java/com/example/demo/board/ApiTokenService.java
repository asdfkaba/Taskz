package com.example.demo.board;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Personal API tokens for scripts and tools, managed in the account settings. A token acts as the
 * user who created it, only on one board they can open, and can read, write or both (see
 * ApiTokenFilter for what each allows). It stops working when it is revoked or its user loses
 * access to the board. Everyone sees and revokes only their own tokens.
 */
@Service
public class ApiTokenService {

    static final String PREFIX = "taskz_";
    static final int MAX_NAME_LENGTH = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BoardService boardService;
    private final ApiTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final BoardColumnRepository columnRepository;
    private final BoardLabelRepository labelRepository;

    public ApiTokenService(BoardService boardService, ApiTokenRepository tokenRepository, UserRepository userRepository,
                           BoardColumnRepository columnRepository, BoardLabelRepository labelRepository) {
        this.boardService = boardService;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.columnRepository = columnRepository;
        this.labelRepository = labelRepository;
    }

    public record TokenDto(Integer id, String name, Integer boardId, String boardName, boolean read, boolean write, Instant created) {
        static TokenDto from(ApiToken token) {
            return new TokenDto(token.getId(), token.getName(), token.getBoard().getId(), token.getBoard().getName(),
                    token.isCanRead(), token.isCanWrite(), token.getCreated());
        }
    }

    // What a token may do, for GET /token. Columns and labels are there even without read access:
    // creating a task needs a column id, labelling it label ids.
    public record TokenAccess(Integer boardId, String boardName, boolean read, boolean write,
                              List<BoardDtos.ColumnDto> columns, List<BoardDtos.LabelDto> labels) {
    }

    // token: shown once; only its hash is stored.
    public record NewToken(TokenDto info, String token) {
    }

    @Transactional
    public NewToken create(Integer boardId, String email, String name, boolean read, boolean write) {
        if (boardId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "boardId is required");
        }
        Board board = boardService.findBoard(boardId, email);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must be 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (!read && !write) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A token needs read or write access");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        byte[] key = new byte[32]; // 256 random bits
        RANDOM.nextBytes(key);
        String plain = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        ApiToken token = tokenRepository.save(new ApiToken(user, board, trimmed, TokenService.hash(plain), read, write, Instant.now()));
        return new NewToken(TokenDto.from(token), plain);
    }

    /** The user's own tokens, for all boards. */
    @Transactional(readOnly = true)
    public List<TokenDto> list(String email) {
        return tokenRepository.findOwn(email).stream().map(TokenDto::from).toList();
    }

    @Transactional
    public void revoke(int tokenId, String email) {
        if (tokenRepository.deleteOwn(tokenId, email) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found");
        }
    }

    /** 404 when the token's user can no longer open its board. */
    @Transactional(readOnly = true)
    public TokenAccess access(ApiTokenAuthentication token) {
        Board board = boardService.findBoard(token.boardId(), token.getName());
        return new TokenAccess(board.getId(), board.getName(), token.canRead(), token.canWrite(),
                columnRepository.findByBoardOrderByPosition(board).stream().map(BoardDtos.ColumnDto::from).toList(),
                labelRepository.findByBoardOrderById(board).stream().map(BoardDtos.LabelDto::from).toList());
    }

    /** Empty for anything that isn't a current token. */
    @Transactional(readOnly = true)
    public Optional<ApiTokenAuthentication> authenticate(String plain) {
        if (plain == null || !plain.startsWith(PREFIX) || plain.length() > 100) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHash(TokenService.hash(plain))
                .map(t -> new ApiTokenAuthentication(t.getUser().getEmail(), t.getBoard().getId(), t.isCanRead(), t.isCanWrite()));
    }
}
