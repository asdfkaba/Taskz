package com.example.demo.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Integer> {

    @Query("select t from ApiToken t join fetch t.user where t.tokenHash = :tokenHash")
    Optional<ApiToken> findByTokenHash(String tokenHash);

    @Query("select t from ApiToken t join fetch t.board where t.user.email = :email order by t.created, t.id")
    List<ApiToken> findOwn(String email);

    /** 0 if the token doesn't exist or belongs to someone else. */
    @Modifying
    @Query("delete from ApiToken t where t.id = :id and t.user.id in (select u.id from User u where u.email = :email)")
    int deleteOwn(Integer id, String email);
}
