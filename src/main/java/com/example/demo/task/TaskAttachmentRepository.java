package com.example.demo.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Integer> {
    // Newest first; id breaks ties.
    List<TaskAttachment> findByTaskOrderByCreatedDescIdDesc(Task task);

    Optional<TaskAttachment> findByIdAndTaskUserEmail(Integer id, String email);

    // Bytes used by the attachments of all tasks of a user, for the storage limit.
    @Query("select coalesce(sum(a.size), 0) from TaskAttachment a where a.task.user.email = :email")
    long totalSizeOfUser(String email);

    @Query("select a.storageKey from TaskAttachment a")
    List<String> findAllStorageKeys();
}
