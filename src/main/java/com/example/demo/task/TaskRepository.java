package com.example.demo.task;

import com.example.demo.board.Board;
import com.example.demo.board.BoardColumn;
import com.example.demo.board.BoardLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Integer> {

    // Archived tasks are left out unless a query says otherwise: they are not on the board.

    @Query("select t from Task t where t.boardColumn = :column and t.archivedAt is null order by t.position")
    List<Task> findByBoardColumnOrderByPosition(BoardColumn column);

    @Query("select count(t) from Task t where t.boardColumn = :column and t.archivedAt is null")
    int countByBoardColumn(BoardColumn column);

    List<Task> findByBoardColumnAndArchivedAtIsNotNull(BoardColumn column);

    // Tasks that entered the column after the first and not after the second time, in the column's order.
    @Query("""
            select t from Task t where t.boardColumn = :column and t.archivedAt is null
              and t.columnEntered > :after and t.columnEntered <= :until
            order by t.position""")
    List<Task> findEnteredBetween(BoardColumn column, Instant after, Instant until);

    // With the archived ones, for deleting the board.
    List<Task> findByBoardColumnBoard(Board board);

    boolean existsByBoardColumnBoardAndArchivedAtIsNotNull(Board board);

    // The board's archive, last archived first.
    @Query("select t from Task t where t.boardColumn.board = :board and t.archivedAt is not null order by t.archivedAt desc, t.id desc")
    List<Task> findArchived(Board board);

    // With the archived ones: a deleted label goes off them too.
    List<Task> findByLabelsContaining(BoardLabel label);

    @Query("""
            select t from Task t where t.boardColumn.board = :board and t.archivedAt is null
            order by t.boardColumn.position, t.position""")
    List<Task> findByBoardInOrder(Board board);

    // Tasks with a due date, soonest first. The last column of a board counts as done, unless it is the only one.
    @Query("""
            select t from Task t join fetch t.boardColumn c join fetch c.board b
            where b in :boards and t.due_date is not null and t.archivedAt is null
              and (c.position < (select max(c2.position) from BoardColumn c2 where c2.board = b)
                   or (select count(c3) from BoardColumn c3 where c3.board = b) = 1)
            order by t.due_date, t.id""")
    List<Task> findDue(Collection<Board> boards);
}
