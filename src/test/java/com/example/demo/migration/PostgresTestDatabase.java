package com.example.demo.migration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * One PostgreSQL 15 container (the production version) for all migration tests.
 * Each test class gets its own database in it, so they don't see each other's tables.
 */
final class PostgresTestDatabase {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:15-alpine");

    static {
        CONTAINER.start();
    }

    private PostgresTestDatabase() {
    }

    /** Creates an empty database and points Spring (datasource, Flyway, Hibernate validate) at it. */
    static String register(String name, DynamicPropertyRegistry registry) {
        execute(CONTAINER.getJdbcUrl(), "CREATE DATABASE " + name);
        String url = urlOf(name);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", CONTAINER::getUsername);
        registry.add("spring.datasource.password", CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "1");
        return url;
    }

    static String urlOf(String database) {
        return CONTAINER.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    static void execute(String url, String sql) {
        try (Connection connection = DriverManager.getConnection(url, CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static String resource(String path) {
        try (var in = Objects.requireNonNull(PostgresTestDatabase.class.getResourceAsStream(path), path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
