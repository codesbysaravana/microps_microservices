package com.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.auth.domain.User;

public record UserProfileResponse(
        UUID id,
        String name,
        String email,
        Instant createdAt
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt()
        );
    }
}

//why, for responding to the /me endpoint (user request user)