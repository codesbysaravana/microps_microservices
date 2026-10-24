package com.auth.infrastructure.redis.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.auth.domain.Session;
import com.auth.infrastructure.redis.RedisConnection;

import io.lettuce.core.api.async.RedisAsyncCommands;


public class RedisSessionRepository {

    private final RedisAsyncCommands<String, String> redis;

    public RedisSessionRepository() {
        this.redis = RedisConnection.getAsyncCommands();
    }
    
    public CompletableFuture<Session> createSession(UUID userId, Duration idleTTL, Duration maxTTL) {
        String sid = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant absoluteExpiry = now.plus(maxTTL);

        String sessionKey = "auth:sess:" + sid;
        String userSessionsKey = "auth:user:" + userId + ":sessions";

        Map<String, String> fields = new HashMap<>();
        fields.put("userId", userId.toString());
        fields.put("createdAt", String.valueOf(now.toEpochMilli()));
        fields.put("lastAccessedAt", String.valueOf(now.toEpochMilli()));
        fields.put("absoluteExpiry", String.valueOf(absoluteExpiry.toEpochMilli()));

        return redis.hset(sessionKey, fields)
                .thenCompose(r -> redis.expire(sessionKey, idleTTL.toSeconds()))
                .thenCompose(r -> redis.sadd(userSessionsKey, sid))
                .thenCompose(r -> redis.expire(userSessionsKey, maxTTL.toSeconds()))
                .thenApply(r -> new Session(sid, userId, now, now, absoluteExpiry))
                .toCompletableFuture();
    }

    public CompletableFuture<Optional<Session>> getSession(String sid, Duration idleTTL) {
        String sessionKey = "auth:sess:" + sid;

        return redis.hgetall(sessionKey).thenCompose(map -> {
            if (map == null || map.isEmpty()) { //if no session key present
                return CompletableFuture.completedFuture(Optional.<Session>empty());
            }

            UUID userId = UUID.fromString(map.get("userId"));
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(map.get("createdAt")));
            Instant lastAccessedAt = Instant.ofEpochMilli(Long.parseLong(map.get("lastAccessedAt")));
            Instant absoluteExpiry = Instant.ofEpochMilli(Long.parseLong(map.get("absoluteExpiry")));
            
            Session session = new Session(sid, userId, createdAt, lastAccessedAt, absoluteExpiry);

            if (session.isExpired()) {
                return deleteSession(sid).thenApply(v -> Optional.<Session>empty());
            }
            Instant now = Instant.now();
            return redis.hset(sessionKey, "lastAccessedAt", String.valueOf(now.toEpochMilli()))
                    .thenCompose(r -> redis.expire(sessionKey, idleTTL.toSeconds()))
                    .thenApply(r -> Optional.of(new Session(sid, userId, createdAt, now, absoluteExpiry)));
        }).toCompletableFuture();
    }

    public CompletableFuture<Void> deleteSession(String sid) {
        String sessionKey = "auth:sess:" + sid;
        return redis.hget(sessionKey, "userId").thenCompose(userIdStr -> {
            if (userIdStr != null && !userIdStr.isBlank()) {
                String userSessionsKey = "auth:user:" + userIdStr + ":sessions";
                return redis.del(sessionKey)
                        .thenCompose(r -> redis.srem(userSessionsKey, sid))
                        .thenApply(r -> (Void) null);
            }
            return redis.del(sessionKey).thenApply(r -> (Void) null);
        }).toCompletableFuture();
    }

    public CompletableFuture<Void> revokeAllUserSessions(UUID userId) {
        String userSessionsKey = "auth:user:" + userId + ":sessions";
        return redis.smembers(userSessionsKey).thenCompose(sids -> {
            if (sids == null || sids.isEmpty()) {
                return CompletableFuture.completedFuture((Void) null);
            }
            // Delete each session key
            CompletableFuture<?>[] deleteFutures = sids.stream()
                    .map(sid -> redis.del("auth:sess:" + sid).toCompletableFuture())
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(deleteFutures)
                    .thenCompose(v -> redis.del(userSessionsKey))
                    .thenApply(r -> (Void) null);
        }).toCompletableFuture();
    }

}