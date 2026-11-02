package com.example.demo.task;

import com.example.demo.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Integer> {
    // Newest first; id breaks ties.
    List<TaskAttachment> findByTaskOrderByCreatedDescIdDesc(Task task);


    // Bytes used by the attachments on all boards of a user, for the storage limit.
    @Query("select coalesce(sum(a.size), 0) from TaskAttachment a where a.task.boardColumn.board.user = :owner")
    long totalSizeOfBoardsOf(User owner);

    @Query("select a.storageKey from TaskAttachment a")
    List<String> findAllStorageKeys();
}
