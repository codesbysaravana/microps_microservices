package com.auth.infrastructure.db.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.auth.domain.User;
import com.auth.infrastructure.db.NeonDatabaseConnection;

public class PostgresUserRepository {
    
    public User createUser(String name, String email, String passwordHash) throws SQLException {
        String sql = "INSERT INTO users (name, email, password_hash) " +  "VALUES (?, ?, ?) " + "RETURNING id, name, email, password_hash, email_verified_at, created_at, updated_at, deleted_at;";

        try (Connection conn = NeonDatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, email.trim().toLowerCase());
            ps.setString(3, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRowToUser(rs);
                }
            }
        }
        throw new SQLException("Failed to create user.");
    }

    public Optional<User> findByUsername(String username) throws SQLException {
        String sql = "SELECT id, name, email, password_hash, email_verified_at, created_at, updated_at, deleted_at " +
                     "FROM users " +
                     "WHERE lower(name) = lower(?) AND deleted_at IS NULL;";
        try (Connection conn = NeonDatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<User> findByEmail(String email) throws SQLException {
        String sql = "SELECT id, name, email, password_hash, email_verified_at, created_at, updated_at, deleted_at " +
                     "FROM users " +
                     "WHERE lower(email) = lower(?) AND deleted_at IS NULL;";
        try (Connection conn = NeonDatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToUser(rs));
                }
            }
        }
        return Optional.empty();
    }


    private User mapRowToUser(ResultSet rs) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        String name = rs.getString("name");
        String email = rs.getString("email");
        String passwordHash = rs.getString("password_hash");

        Timestamp verifiedTs = rs.getTimestamp("email_verified_at");
        Instant emailVerifiedAt = verifiedTs != null ? verifiedTs.toInstant() : null;

        Timestamp createdTs = rs.getTimestamp("created_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : null;

        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : null;

        Timestamp deletedTs = rs.getTimestamp("deleted_at");
        Instant deletedAt = deletedTs != null ? deletedTs.toInstant() : null;

        return new User(id, name, email, passwordHash, emailVerifiedAt, createdAt, updatedAt, deletedAt);
    }
}