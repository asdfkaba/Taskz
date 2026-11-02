package com.example.demo.auth.jwt;

import com.example.demo.user.User;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private User user;

    /** SHA-256 of the token (hex); the token itself is only in the user's cookie. */
    @Column(nullable = false, unique = true)
    private String token;

    /** The token itself, only known right after it was created, for the cookie. */
    @Transient
    private String plainToken;

    private Instant issuedAt;
    private Instant expiresAt;
    private String userAgent;
    private String ipAddress;
    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

}