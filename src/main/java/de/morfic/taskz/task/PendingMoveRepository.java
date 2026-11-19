package de.morfic.taskz.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PendingMoveRepository extends JpaRepository<PendingMove, Integer> {
    List<PendingMove> findByDueAtLessThanEqualOrderByDueAt(Instant now);
}
