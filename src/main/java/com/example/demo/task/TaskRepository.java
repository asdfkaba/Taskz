package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Integer> {

    List<Task> findByBoardColumnOrderByPosition(BoardColumn column);
    int countByBoardColumn(BoardColumn column);

    List<Task> findByLabelsContaining(BoardLabel label);

    @Query("select t from Task t where t.boardColumn.board = :board order by t.boardColumn.position, t.position")
    List<Task> findByBoardInOrder(Board board);
}
