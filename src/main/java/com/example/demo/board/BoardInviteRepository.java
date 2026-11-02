package com.example.demo.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface BoardInviteRepository extends JpaRepository<BoardInvite, Integer> {
    @Query("select i from BoardInvite i where i.tokenHash = :tokenHash and i.expires > :now")
    Optional<BoardInvite> findValid(String tokenHash, Instant now);

    /** Uses up the invite; 0 if it was used (or ran out) in the meantime. */
    @Modifying
    @Query("delete from BoardInvite i where i.id = :id")
    int consume(Integer id);

    @Modifying
    @Query("delete from BoardInvite i where i.expires < :now")
    void deleteExpired(Instant now);
}
