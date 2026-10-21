package com.auth.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.auth.config.DatabaseConfig;
import com.auth.domain.User;
import com.auth.domain.UserCredential;
public class UserRepository {

    public User save(User user) throws SQLException {
        boolean hasCustomId = user.getId() != null;
        String sql;

        if (hasCustomId) {
            sql = "INSERT INTO users (id, name, email, password_hash, email_verified_at) " +
                  "VALUES (?, ?, ?, ?, ?) " +
                  "RETURNING id, name, email, password_hash, email_verified_at, created_at, updated_at, deleted_at;";
        } else {
            sql = "INSERT INTO users (name, email, password_hash, email_verified_at) " +
                  "VALUES (?, ?, ?, ?) " +
                  "RETURNING id, name, email, password_hash, email_verified_at, created_at, updated_at, deleted_at;";
        }

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            if (hasCustomId) {
                ps.setObject(idx++, user.getId());
            }

            ps.setString(idx++, user.getName());
            ps.setString(idx++, user.getEmail() != null ? user.getEmail().trim().toLowerCase() : null);
            ps.setString(idx++, user.getPasswordHash());

            if (user.getEmailVerifiedAt() != null) {
                ps.setTimestamp(idx++, Timestamp.from(user.getEmailVerifiedAt()));
            } else {
                ps.setNull(idx++, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRowToUser(rs);
                }
            }
        }

        throw new SQLException("Failed to insert user into database.");
    }

    /**
     * Finds a user by email (case-insensitive lookup, excluding soft-deleted users).
     */
    public Optional<User> findByEmail(String email) throws SQLException {
        if (email == null) {
            return Optional.empty();
        }

        String sql = "SELECT id, name, email, password_hash, email_verified_at, created_at, updated_at, deleted_at " +
                     "FROM users " +
                     "WHERE lower(email) = lower(?) AND deleted_at IS NULL;";

        try (Connection conn = DatabaseConfig.getConnection();
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

    /**
     * Finds a user by ID (excluding soft-deleted users).
     */
    public Optional<User> findById(UUID id) throws SQLException {
        if (id == null) {
            return Optional.empty();
        }

        String sql = "SELECT id, name, email, password_hash, email_verified_at, created_at, updated_at, deleted_at " +
                     "FROM users " +
                     "WHERE id = ? AND deleted_at IS NULL;";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setObject(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToUser(rs));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Retrieves credentials (password hash and verification status) by email for authentication.
     */
    public Optional<UserCredential> findCredentialByEmail(String email) throws SQLException {
        if (email == null) {
            return Optional.empty();
        }

        String sql = "SELECT id, email, password_hash, email_verified_at " +
                     "FROM users " +
                     "WHERE lower(email) = lower(?) AND deleted_at IS NULL;";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, email.trim());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID id = (UUID) rs.getObject("id");
                    String userEmail = rs.getString("email");
                    String passwordHash = rs.getString("password_hash");
                    Timestamp verifiedTs = rs.getTimestamp("email_verified_at");
                    boolean verified = (verifiedTs != null);

                    return Optional.of(new UserCredential(id, userEmail, passwordHash, verified));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Checks if an email is already in use by an active user.
     */
    public boolean existsByEmail(String email) throws SQLException {
        if (email == null) {
            return false;
        }

        String sql = "SELECT 1 FROM users WHERE lower(email) = lower(?) AND deleted_at IS NULL;";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, email.trim());

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Updates an existing user's password hash.
     */
    public boolean updatePassword(UUID id, String newPasswordHash) throws SQLException {
        String sql = "UPDATE users SET password_hash = ? WHERE id = ? AND deleted_at IS NULL;";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, newPasswordHash);
            ps.setObject(2, id);

            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Marks a user's email as verified.
     */
    public boolean markEmailVerified(UUID id) throws SQLException {
        String sql = "UPDATE users SET email_verified_at = CURRENT_TIMESTAMP WHERE id = ? AND deleted_at IS NULL;";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setObject(1, id);

            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Soft deletes a user by setting deleted_at.
     */
    public boolean softDelete(UUID id) throws SQLException {
        String sql = "UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND deleted_at IS NULL;";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setObject(1, id);

            return ps.executeUpdate() > 0;
        }
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
