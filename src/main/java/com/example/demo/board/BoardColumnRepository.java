package com.example.demo.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoardColumnRepository extends JpaRepository<BoardColumn, Integer> {
    List<BoardColumn> findByBoardOrderByPosition(Board board);
}
