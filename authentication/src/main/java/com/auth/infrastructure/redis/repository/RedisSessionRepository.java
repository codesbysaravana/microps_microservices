package com.auth.infrastructure.redis.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.auth.domain.Session;
import com.auth.infrastructure.redis.RedisConnection;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;

public class RedisSessionRepository {

    private final RedisAsyncCommands<String, String> redis;

    public RedisSessionRepository() {
        this.redis = RedisConnection.getAsyncCommands();
    }
    
    /* public CompletableFuture<Session> createSession(UUID userId, Duration idleTTL, Duration maxTTL) {
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
    } */
   
    public record SessionWithTokens(String sid, UUID userId, String refreshToken, Instant createdAt, Instant absoluteExpiry) {}
    
    public enum RotationStatus { SUCCESS, GRACE_HIT, REPLAY_DETECTED }
    
    public record RotationResult(RotationStatus status, String sid, UUID userId, String refreshToken, String accessToken) {}


    private static final String CREATE_SESSION_LUA =
            "redis.call('HSET', KEYS[1], " +
            "  'sid', ARGV[1], " +
            "  'userId', ARGV[2], " +
            "  'createdAt', ARGV[3], " +
            "  'lastAccessedAt', ARGV[4], " +
            "  'absoluteExpiry', ARGV[5], " +
            "  'currentRt', ARGV[8])\n" +
            "redis.call('EXPIRE', KEYS[1], ARGV[6])\n" +
            "redis.call('SADD', KEYS[2], ARGV[1])\n" +
            "redis.call('EXPIRE', KEYS[2], ARGV[7])\n" +
            "redis.call('HSET', KEYS[3], 'sid', ARGV[1], 'userId', ARGV[2], 'status', 'ACTIVE')\n" +
            "redis.call('EXPIRE', KEYS[3], ARGV[7])\n" +
            "return 'OK'";

    public CompletableFuture<SessionWithTokens> createSessionWithTokens(UUID userId, Duration idleTTL, Duration maxTTL) {
        String sid = UUID.randomUUID().toString();
        String refreshToken = sid + "." + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant absoluteExpiry = now.plus(maxTTL);

        String sessionKey = "auth:sess:" + sid;
        String userSessionsKey = "auth:user:" + userId + ":sessions";
        String rtKey = "auth:rt:" + refreshToken;

        String[] keys = new String[]{ sessionKey, userSessionsKey, rtKey };
        String[] args = new String[]{
                sid,
                userId.toString(),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(absoluteExpiry.toEpochMilli()),
                String.valueOf(idleTTL.toSeconds()),
                String.valueOf(maxTTL.toSeconds()),
                refreshToken
        };

        return redis.eval(CREATE_SESSION_LUA, ScriptOutputType.STATUS, keys, args)
                .thenApply(status -> new SessionWithTokens(sid, userId, refreshToken, now, absoluteExpiry))
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

    private static final String DELETE_SESSION_LUA =
            "local userId = redis.call('HGET', KEYS[1], 'userId')\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "if userId then\n" +
            "  redis.call('SREM', 'auth:user:' .. userId .. ':sessions', ARGV[1])\n" +
            "end\n" +
            "return 'OK'";

    public CompletableFuture<Void> deleteSession(String sid) {
        String sessionKey = "auth:sess:" + sid;
        return redis.eval(DELETE_SESSION_LUA, ScriptOutputType.STATUS, new String[]{ sessionKey }, sid)
                .thenApply(r -> (Void) null)
                .toCompletableFuture();
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

        // ROTATION STATE MACHINE IN LUA:
    // Case 1: Token in 10s Grace Window (Two-Tab race) -> Returns cached pair (idempotent, no logout!)
    // Case 2: Token is neither Active nor in Grace -> REPLAY ATTACK DETECTED!
    // Case 3: Token is Active -> Rotates, creates new RT, caches pair in 10s grace storage
    private static final String ROTATE_RT_LUA =
            "local consumed = redis.call('HMGET', KEYS[2], 'newRt', 'newAt')\n" +
            "if consumed[1] and consumed[2] then\n" +
            "  return {'GRACE', consumed[1], consumed[2]}\n" +
            "end\n" +
            "local activeData = redis.call('HMGET', KEYS[1], 'sid', 'userId', 'status')\n" +
            "local sid = activeData[1]\n" +
            "local userId = activeData[2]\n" +
            "local status = activeData[3]\n" +
            "if not sid or status ~= 'ACTIVE' then\n" +
            "  return {'REVOKED'}\n" +
            "end\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "redis.call('HSET', KEYS[2], 'newRt', ARGV[1], 'newAt', ARGV[2], 'sid', sid, 'userId', userId)\n" +
            "redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))\n" +
            "redis.call('HSET', KEYS[3], 'sid', sid, 'userId', userId, 'status', 'ACTIVE')\n" +
            "redis.call('EXPIRE', KEYS[3], tonumber(ARGV[3]))\n" +
            "local sessionKey = 'auth:sess:' .. sid\n" +
            "redis.call('HSET', sessionKey, 'currentRt', ARGV[1])\n" +
            "return {'OK', sid, userId}";

    public CompletableFuture<RotationResult> rotateRefreshToken(String oldRt, String newRt, String newAt, Duration rtTTL, Duration graceTTL) {
        String oldRtKey = "auth:rt:" + oldRt;
        String consumedKey = "auth:rt_consumed:" + oldRt;
        String newRtKey = "auth:rt:" + newRt;

        String[] keys = new String[]{ oldRtKey, consumedKey, newRtKey };
        String[] args = new String[]{
                newRt,
                newAt,
                String.valueOf(rtTTL.toSeconds()),
                String.valueOf(graceTTL.toSeconds())
        };

        return redis.eval(ROTATE_RT_LUA, ScriptOutputType.MULTI, keys, args)
                .thenApply(res -> {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> list = (java.util.List<Object>) res;
                    String code = list.get(0).toString();
                    if ("GRACE".equals(code)) {
                        return new RotationResult(RotationStatus.GRACE_HIT, null, null, list.get(1).toString(), list.get(2).toString());
                    } else if ("REVOKED".equals(code)) {
                        return new RotationResult(RotationStatus.REPLAY_DETECTED, null, null, null, null);
                    } else {
                        String sid = list.get(1).toString();
                        UUID userId = UUID.fromString(list.get(2).toString());
                        return new RotationResult(RotationStatus.SUCCESS, sid, userId, newRt, newAt);
                    }
                }).toCompletableFuture();
    }


}

//why shifting creation and deleteion to lua
//since imagine the server crashes while creating sid and stops before maxTTL, then that key would forver exiist after restart
// now for that case we need the creation and deletion alone to be completly atomic
// create with TTL + session or deletion
// hence the lua scripts 


//updating for refresh tokens in redis