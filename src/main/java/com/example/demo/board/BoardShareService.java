package com.example.demo.board;

import com.example.demo.board.BoardDtos.SharedBoard;
import com.example.demo.task.TaskDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Read-only share links. Only the owner can see, create or revoke a board's link;
 * reading a board through its link needs no login. For now a board has at most one link.
 */
@Service
public class BoardShareService {

    private final BoardService boardService;
    private final BoardShareRepository shareRepository;

    public BoardShareService(BoardService boardService, BoardShareRepository shareRepository) {
        this.boardService = boardService;
        this.shareRepository = shareRepository;
    }

    @Transactional(readOnly = true)
    public Optional<BoardShare> getShare(int boardId, String email) {
        return shareRepository.findByBoard(boardService.findOwnBoard(boardId, email));
    }

    /** Creates the board's link. An existing link is replaced, so the old one stops working. */
    @Transactional
    public BoardShare createShare(int boardId, String email) {
        Board board = boardService.findOwnBoard(boardId, email);
        shareRepository.deleteByBoard(board);
        return shareRepository.save(new BoardShare(board));
    }

    @Transactional
    public void revokeShare(int boardId, String email) {
        shareRepository.deleteByBoard(boardService.findOwnBoard(boardId, email));
    }

    /**
     * The board behind a link path (see {@link ShareLinks}). Unknown, revoked and malformed links
     * all answer 404, so nothing can be learned from the answer.
     */
    @Transactional(readOnly = true)
    public SharedBoard sharedBoard(String path) {
        BoardShare share = ShareLinks.token(path)
                .flatMap(shareRepository::findByToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
        Board board = share.getBoard();
        return new SharedBoard(board.getName(), ShareLinks.path(board.getSlug(), share.getToken()),
                boardService.columnsOf(board), boardService.labelsOf(board),
                boardService.tasksOf(board).stream().map(TaskDto::withoutAttachments).toList());
    }
}
