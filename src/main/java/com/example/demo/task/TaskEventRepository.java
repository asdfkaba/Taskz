package com.example.demo.task;

import com.example.demo.board.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskEventRepository extends JpaRepository<TaskEvent, Integer> {
    // Newest first; id breaks ties between events in the same instant.
    List<TaskEvent> findByTaskIdOrderByAtDescIdDesc(Integer taskId);

    // Loads and removes them, so none stays in the session pointing at the deleted board (Hibernate 6.6 checks).
    void deleteByBoard(Board board);
}
