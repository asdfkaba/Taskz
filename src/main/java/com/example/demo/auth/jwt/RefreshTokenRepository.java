package com.example.demo.auth.jwt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    void deleteByToken(String refreshTokenValue);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    void deleteExpired(Instant now);

    @Query("select t from RefreshToken t where t.user.email = :email and t.expiresAt > :now order by t.issuedAt desc, t.id desc")
    List<RefreshToken> findActive(String email, Instant now);

    /** 0 if the login doesn't exist or belongs to someone else. */
    @Modifying
    @Query("delete from RefreshToken t where t.id = :id and t.user.id in (select u.id from User u where u.email = :email)")
    int deleteOwn(Long id, String email);

    /** All logins of the user except the one whose hash is keep (pass "" to keep none). */
    @Modifying
    @Query("delete from RefreshToken t where t.token <> :keep and t.user.id in (select u.id from User u where u.email = :email)")
    int deleteOthers(String email, String keep);
}
