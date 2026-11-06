package com.example.demo.migration;

import com.example.demo.task.RuleEngine;
import com.example.demo.auth.jwt.TokenService;
import com.example.demo.board.Board;
import com.example.demo.board.BoardService;
import com.example.demo.board.RulePayload;
import com.example.demo.task.TaskHistoryService;
import com.example.demo.user.User;
import com.example.demo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An empty database (local, CI): Flyway runs all migrations, and Hibernate's validation
 * accepts the result. Fails if an entity changes without a matching migration.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BoardService.class, RuleEngine.class, TaskHistoryService.class})
@Testcontainers(disabledWithoutDocker = true)
class FreshDatabaseMigrationTests {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register("fresh", registry);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardService boardService;

    @Test
    void allMigrationsRunFromScratch() {
        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(applied).startsWith("1", "2", "3");
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where type = 'BASELINE'", Integer.class)).isZero();
    }

    @Test
    void entitiesCanBeStored() {
        Board board = boardService.createDefaultBoard(userRepository.save(new User("Alice", "alice@example.com")));
        assertThat(board.getId()).isNotNull();
        assertThat(boardService.getBoard(board.getId(), "alice@example.com").columns()).hasSize(3);
    }

    // V12 turns SET_DUE_IN rules into note reminders; its UPDATE is run again on such a rule.
    @Test
    void oldDueDateRulesBecomeNoteReminders() throws Exception {
        Board board = boardService.createDefaultBoard(userRepository.save(new User("Alice", "alice@example.com")));
        Integer column = jdbc.queryForObject("select min(id) from board_column where board_id = ?", Integer.class, board.getId());
        jdbc.update("insert into board_rule (column_id, trigger, action, payload, enabled) values (?, 'ENTERED_COLUMN', 'SET_DUE_IN', '{\"days\":14}', true)", column);

        String migration = new String(new ClassPathResource("db/migration/V12__note_reminders.sql").getInputStream().readAllBytes());
        jdbc.update(migration.substring(migration.indexOf("UPDATE board_rule")));

        Map<String, Object> rule = jdbc.queryForMap("select action, payload from board_rule where column_id = ?", column);
        assertThat(rule.get("action")).isEqualTo("ADD_NOTE_REMINDER");
        RulePayload payload = RulePayload.parse((String) rule.get("payload"));
        assertThat(payload.text()).isEqualTo("Follow up");
        assertThat(payload.days()).isEqualTo(14);
    }

    // V3 and V6 delete notes and attachments in the database when their task is deleted; checked on
    // PostgreSQL itself.
    @Test
    void deletingATaskDeletesItsNotesAndAttachmentsInTheDatabase() {
        Board board = boardService.createDefaultBoard(userRepository.save(new User("Bob", "bob@example.com")));
        Integer column = jdbc.queryForObject("select min(id) from board_column where board_id = ?", Integer.class, board.getId());
        Integer task = jdbc.queryForObject(
                "insert into task (title, position, board_column_id) values ('T', 0, ?) returning id", Integer.class, column);
        jdbc.update("insert into task_note (task_id, text, created) values (?, 'note', now())", task);
        jdbc.update("insert into task_attachment (task_id, file_name, content_type, size, storage_key, created) "
                + "values (?, 'cv.pdf', 'application/pdf', 3, gen_random_uuid()::text, now())", task);

        jdbc.update("delete from task where id = ?", task);

        assertThat(jdbc.queryForObject("select count(*) from task_note", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from task_attachment", Integer.class)).isZero();
    }

    // V7: one account per email, as logins find accounts by it. Several without an email stay possible.
    @Test
    void emailsAreUnique() {
        jdbc.update("insert into \"user\" (name, email) values ('C', null)");
        jdbc.update("insert into \"user\" (name, email) values ('D', null)");
        jdbc.update("insert into \"user\" (name, email) values ('A', 'same@example.com')");
        // Last: the failed insert aborts the test's transaction.
        assertThatThrownBy(() -> jdbc.update("insert into \"user\" (name, email) values ('B', 'same@example.com')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // V8 hashes stored refresh tokens in SQL; that must match TokenService.hash, or logins break.
    @Test
    void refreshTokensAreHashedLikeTheApp() {
        String sql = jdbc.queryForObject("select encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class, "a-token");
        assertThat(sql).isEqualTo(TokenService.hash("a-token"));
    }
}
