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
        }

        throw new SQLException("Failed to insert user into database.");
    }
}
