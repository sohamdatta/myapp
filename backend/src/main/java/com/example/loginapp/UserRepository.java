package com.example.loginapp;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes the users table. */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the stored password hash for the username, if the user exists. */
    public Optional<String> findPasswordHash(String username) {
        List<String> hashes = jdbc.queryForList(
                "SELECT password_hash FROM users WHERE username = ?", String.class, username);
        return hashes.isEmpty() ? Optional.empty() : Optional.of(hashes.get(0));
    }

    public void create(String username, String passwordHash) {
        jdbc.update("INSERT INTO users (username, password_hash) VALUES (?, ?)", username, passwordHash);
    }
}
