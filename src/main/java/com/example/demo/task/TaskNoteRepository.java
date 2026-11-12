package com.example.demo.task;

import com.example.demo.board.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskNoteRepository extends JpaRepository<TaskNote, Integer> {
    // Newest first; id breaks ties between notes written in the same instant.
    List<TaskNote> findByTaskOrderByCreatedDescIdDesc(Task task);

    void deleteByTaskAndRuleIdIn(Task task, Collection<Integer> ruleIds);

    // The user's reminders on the given boards, soonest first; skips the last column and archived tasks like TaskRepository.findDue.
    @Query("""
            select n from TaskNote n join fetch n.task t join fetch t.boardColumn c join fetch c.board b
            where n.user.email = :email and n.remindAt is not null and b in :boards and t.archivedAt is null
              and (c.position < (select max(c2.position) from BoardColumn c2 where c2.board = b)
                   or (select count(c3) from BoardColumn c3 where c3.board = b) = 1)
            order by n.remindAt, n.id""")
    List<TaskNote> findReminders(String email, Collection<Board> boards);

}
