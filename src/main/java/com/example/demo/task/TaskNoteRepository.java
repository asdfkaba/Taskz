package com.example.demo.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskNoteRepository extends JpaRepository<TaskNote, Integer> {
    // Newest first; id breaks ties between notes written in the same instant.
    List<TaskNote> findByTaskOrderByCreatedDescIdDesc(Task task);

}
