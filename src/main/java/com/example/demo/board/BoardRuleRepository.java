package com.example.demo.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoardRuleRepository extends JpaRepository<BoardRule, Integer> {
    List<BoardRule> findByColumnBoardOrderById(Board board);

    List<BoardRule> findByColumnAndTriggerAndEnabledTrueOrderById(BoardColumn column, String trigger);

    List<BoardRule> findByActionAndEnabledTrue(String action);

    void deleteByColumn(BoardColumn column);

    void deleteByColumnBoard(Board board);
}
