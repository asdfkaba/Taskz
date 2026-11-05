package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Integer> {

    List<Task> findByBoardColumnOrderByPosition(BoardColumn column);
    int countByBoardColumn(BoardColumn column);

    List<Task> findByLabelsContaining(BoardLabel label);

    @Query("select t from Task t where t.boardColumn.board = :board order by t.boardColumn.position, t.position")
    List<Task> findByBoardInOrder(Board board);

    // Tasks with a due date, soonest first. The last column of a board counts as done, unless it is the only one.
    @Query("""
            select t from Task t join fetch t.boardColumn c join fetch c.board b
            where b in :boards and t.due_date is not null
              and (c.position < (select max(c2.position) from BoardColumn c2 where c2.board = b)
                   or (select count(c3) from BoardColumn c3 where c3.board = b) = 1)
            order by t.due_date, t.id""")
    List<Task> findDue(Collection<Board> boards);
}
