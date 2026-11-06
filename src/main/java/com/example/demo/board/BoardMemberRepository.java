package com.example.demo.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BoardMemberRepository extends JpaRepository<BoardMember, Integer> {
    boolean existsByBoardAndUserEmail(Board board, String email);

    List<BoardMember> findByBoardOrderByJoinedAscIdAsc(Board board);

    // Loads and removes them, like TaskEventRepository.deleteByBoard.
    void deleteByBoard(Board board);

    @Query("select m.board from BoardMember m where m.user.email = :email order by m.joined, m.id")
    List<Board> boardsOf(String email);

    @Modifying
    @Query("delete from BoardMember m where m.board = :board and m.user.id = :userId")
    int deleteMember(Board board, int userId);

    @Modifying
    @Query("delete from BoardMember m where m.board = :board and m.user.email = :email")
    int deleteMember(Board board, String email);
}
