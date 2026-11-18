package com.example.demo.user;

import com.example.demo.task.Task;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name="`user`")
@Data
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Integer id;
    private String name;
    // Accounts are matched by email at login, so one account per address (see V7).
    @Column(unique = true)
    private String email;
    // Opens /admin. Only set by hand in the database (see V20).
    private boolean admin;
    // null for accounts created before V20.
    @CreationTimestamp
    private Instant createdAt;

    public User(String name, String email) {
        this.name = name;
        this.email = email;
    }

    @OneToMany(mappedBy = "user")
    @JsonIgnore
    private List<Task> tasks;

}
