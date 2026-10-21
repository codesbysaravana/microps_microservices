package com.auth.domain;

import java.util.Objects;
import java.util.UUID;

public class UserCredential {
    private final UUID userId;
    private final String email;
    private final String passwordHash;
    private final boolean emailVerified;

    public UserCredential(UUID userId, String email, String passwordHash, boolean emailVerified) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.emailVerified = emailVerified;
    }

    public static UserCredential fromUser(User user) {
        if (user == null) {
            return null;
        }
        return new UserCredential(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isEmailVerified()
        );
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserCredential that = (UserCredential) o;
        return Objects.equals(userId, that.userId) && Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, email);
    }

    @Override
    public String toString() {
        return "UserCredential{" +
                "userId=" + userId +
                ", email='" + email + '\'' +
                ", emailVerified=" + emailVerified +
                '}';
    }
}
