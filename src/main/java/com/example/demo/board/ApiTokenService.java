package com.example.demo.board;

import com.example.demo.auth.jwt.TokenService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Personal API tokens for scripts and tools, managed in the account settings. A token acts as the
 * user who created it, only on one board they can open, and only within its scopes (see
 * ApiTokenFilter for what each allows). It stops working when it is revoked, expires or its user
 * loses access to the board. Everyone sees and revokes only their own tokens.
 */
@Service
public class ApiTokenService {

    static final String PREFIX = "taskz_";
    static final int MAX_NAME_LENGTH = 50;
    static final int MAX_EXPIRY_DAYS = 365;
    // last_used_at is written at most this often, not on every request.
    private static final Duration USE_PRECISION = Duration.ofMinutes(1);
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

    public record TokenDto(Integer id, String name, Integer boardId, String boardName, List<String> scopes,
                           Instant created, Instant expiresAt, Instant lastUsedAt) {
        static TokenDto from(ApiToken token) {
            return new TokenDto(token.getId(), token.getName(), token.getBoard().getId(), token.getBoard().getName(),
                    values(token.scopeSet()), token.getCreated(), token.getExpiresAt(), token.getLastUsedAt());
        }
    }

    // What a token may do, for GET /token. Columns and labels are there even without tasks:read:
    // creating a task needs a column id, labelling it label ids.
    public record TokenAccess(Integer boardId, String boardName, List<String> scopes, Instant expiresAt,
                              List<BoardDtos.ColumnDto> columns, List<BoardDtos.LabelDto> labels) {
    }

    // token: shown once; only its hash is stored.
    public record NewToken(TokenDto info, String token) {
    }

    @Transactional
    public NewToken create(Integer boardId, String email, String name, List<String> scopes, Integer expiresInDays) {
        if (boardId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "boardId is required");
        }
        Board board = boardService.findBoard(boardId, email);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must be 1 to " + MAX_NAME_LENGTH + " characters");
        }
        Set<ApiScope> valid = validScopes(scopes);
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > MAX_EXPIRY_DAYS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresInDays must be between 1 and " + MAX_EXPIRY_DAYS);
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        byte[] key = new byte[32]; // 256 random bits
        RANDOM.nextBytes(key);
        String plain = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        Instant now = Instant.now();
        Instant expiresAt = expiresInDays == null ? null : now.plus(Duration.ofDays(expiresInDays));
        ApiToken token = tokenRepository.save(new ApiToken(user, board, trimmed, TokenService.hash(plain), valid, now, expiresAt));
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
        return new TokenAccess(board.getId(), board.getName(), values(token.scopes()), token.expiresAt(),
                columnRepository.findByBoardOrderByPosition(board).stream().map(BoardDtos.ColumnDto::from).toList(),
                labelRepository.findByBoardOrderById(board).stream().map(BoardDtos.LabelDto::from).toList());
    }

    /** Empty for anything that isn't a current token. Notes when the token was used. */
    @Transactional
    public Optional<ApiTokenAuthentication> authenticate(String plain) {
        if (plain == null || !plain.startsWith(PREFIX) || plain.length() > 100) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return tokenRepository.findByTokenHash(TokenService.hash(plain))
                .filter(t -> !t.expired(now))
                .map(t -> {
                    if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(now.minus(USE_PRECISION))) {
                        t.used(now);
                    }
                    return new ApiTokenAuthentication(t.getUser().getEmail(), t.getBoard().getId(), t.scopeSet(), t.getExpiresAt());
                });
    }

    private static Set<ApiScope> validScopes(List<String> scopes) {
        Set<ApiScope> result = EnumSet.noneOf(ApiScope.class);
        for (String value : scopes == null ? List.<String>of() : scopes) {
            result.add(ApiScope.of(value).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scope " + value)));
        }
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A token needs at least one scope");
        }
        return result;
    }

    // In ApiScope order, as the API shows them.
    private static List<String> values(Set<ApiScope> scopes) {
        return scopes.stream().sorted().map(ApiScope::value).toList();
    }
}
