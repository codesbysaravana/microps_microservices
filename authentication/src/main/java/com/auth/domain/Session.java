package com.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record Session(
        String sid,
        UUID userId,
        Instant createdAt,
        Instant lastAccessedAt,
        Instant absoluteExpiry
) {
    public boolean isExpired() {
        return Instant.now().isAfter(absoluteExpiry);
    }
}

//for redis, not for postgres db