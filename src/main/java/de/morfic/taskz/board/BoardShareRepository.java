package de.morfic.taskz.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BoardShareRepository extends JpaRepository<BoardShare, Integer> {
    Optional<BoardShare> findByBoard(Board board);
    Optional<BoardShare> findByToken(UUID token);
    void deleteByBoard(Board board);
}
