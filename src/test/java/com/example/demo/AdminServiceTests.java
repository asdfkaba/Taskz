package com.example.demo;

import com.example.demo.board.*;
import com.example.demo.notification.NotificationService;
import com.example.demo.task.*;
import com.example.demo.user.AccountService;
import com.example.demo.user.AdminService;
import com.example.demo.user.AdminService.UserDto;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/** Only admins list and delete users (against H2). */
@DataJpaTest
@Import({AdminService.class, AccountService.class, ApiTokenService.class, BoardMemberService.class, BoardService.class,
        RuleEngine.class, NotificationService.class, LabelService.class, TaskNoteService.class,
        TaskHistoryService.class, TaskAttachmentService.class, AttachmentStorage.class})
class AdminServiceTests {

    static final String ADMIN = "admin@example.com";
    static final String BOB = "bob@example.com";

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void attachments(DynamicPropertyRegistry registry) {
        registry.add("attachments.dir", () -> dir.toString());
    }

    @Autowired AdminService adminService;
    @Autowired BoardService boardService;
    @Autowired UserRepository userRepository;

    User admin;
    User bob;

    @BeforeEach
    void setUp() {
        admin = new User("Admin", ADMIN);
        admin.setAdmin(true);
        admin = userRepository.save(admin);
        bob = userRepository.save(new User("Bob", BOB));
        boardService.createDefaultBoard(bob);
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    @Test
    void anAdminSeesAllUsersWithWhenTheyRegistered() {
        assertThat(adminService.users(ADMIN)).extracting(UserDto::email, UserDto::admin)
                .containsExactly(tuple(ADMIN, true),
                        tuple(BOB, false));
        assertThat(adminService.users(ADMIN)).allSatisfy(u -> assertThat(u.createdAt()).isNotNull());
    }

    @Test
    void othersAreRefused() {
        assertThatThrownBy(() -> adminService.users(BOB)).satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> adminService.deleteUser(BOB, admin.getId(), ADMIN))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThat(userRepository.findById(admin.getId())).isPresent();
    }

    @Test
    void anAdminDeletesAUserAfterTypingTheirEmail() {
        assertThatThrownBy(() -> adminService.deleteUser(ADMIN, bob.getId(), ADMIN))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThat(userRepository.findById(bob.getId())).isPresent();

        adminService.deleteUser(ADMIN, bob.getId(), BOB);
        assertThat(userRepository.findByEmail(BOB)).isEmpty();
        assertThat(boardService.boardsOf(BOB)).isEmpty();
    }

    @Test
    void notTheOwnAccountNorAnUnknownOne() {
        assertThatThrownBy(() -> adminService.deleteUser(ADMIN, admin.getId(), ADMIN))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> adminService.deleteUser(ADMIN, 999_999, BOB))
                .satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }
}
