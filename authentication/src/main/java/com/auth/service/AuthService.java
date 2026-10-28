package com.auth.service;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.auth.domain.User;
import com.auth.dto.AuthResponse;
import com.auth.dto.LoginRequest;
import com.auth.infrastructure.db.repository.PostgresUserRepository;
import com.auth.infrastructure.redis.repository.RedisSessionRepository;

import io.jsonwebtoken.Claims;

public class AuthService {

    private static final ExecutorService BLOCKING_EXECUTOR = new ThreadPoolExecutor(
            8,                          // core threads
            32,                         // max threads under load
            60L, TimeUnit.SECONDS,      // keep-alive for idle threads
            new ArrayBlockingQueue<>(1000), // bounded queue (prevents OutOfMemory)
            new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "blocking-worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.AbortPolicy() // REJECT FAST: If queue is full, throw RejectedExecutionException immediately (never hijack Netty!)
            //saves the backend by shedding load it cant handle
    );


    private final PostgresUserRepository userRepository;
    private final RedisSessionRepository sessionRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    private static final Duration IDLE_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_TTL = Duration.ofDays(7);
    private static final Duration GRACE_TTL = Duration.ofSeconds(10); // 10-second grace period for concurrent tabs

    public AuthService() {
        this(new PostgresUserRepository(), new RedisSessionRepository(), new PasswordService(), new JwtService());
    }

    public AuthService(PostgresUserRepository userRepository, RedisSessionRepository sessionRepository, PasswordService passwordService, JwtService jwtService) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    public CompletableFuture<AuthResponse> login(LoginRequest req) {
        // Step 1: Offload JDBC query + Argon2 CPU work to BLOCKING_EXECUTOR
        return CompletableFuture.supplyAsync(() -> {
            try {
                User user = userRepository.findByEmail(req.getEmail())
                        .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
                if (!passwordService.verify(user.getPasswordHash(), req.getPassword())) {
                    throw new IllegalArgumentException("Invalid credentials");
                }
                return user;
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, BLOCKING_EXECUTOR)
        // Step 2: Once verified, hand off to non-blocking Redis session creation
        .thenCompose(user -> sessionRepository.createSessionWithTokens(user.getId(), IDLE_TTL, MAX_TTL)
                .thenApply(session -> {
                    String token = jwtService.createToken(user.getEmail(), Map.of(
                            "userId", user.getId().toString(),
                            "sid", session.sid()
                    ));
                    return new AuthResponse(token, session.refreshToken(), jwtService.getExpirationMs() / 1000);
                })
        );
    } //check since this is a two wrapped response then, weneed to peel off the internal exceptions in authHandler 

    public CompletableFuture<AuthResponse> signup(LoginRequest req) {
        if (req == null || req.getEmail() == null || req.getPassword() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Email and password are required"));
        }
        // Step 1: Offload email check, Argon2 hashing, and DB insert to BLOCKING_EXECUTOR
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (userRepository.findByEmail(req.getEmail()).isPresent()) {
                    throw new IllegalArgumentException("Email already in use");
                }
                String passwordHash = passwordService.hash(req.getPassword());
                String name = req.getEmail().split("@")[0];
                return userRepository.createUser(name, req.getEmail(), passwordHash);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, BLOCKING_EXECUTOR)
        // Step 2: Create Redis session non-blockingly
        .thenCompose(user -> sessionRepository.createSessionWithTokens(user.getId(), IDLE_TTL, MAX_TTL)
                .thenApply(session -> {
                    String token = jwtService.createToken(user.getEmail(), Map.of(
                            "userId", user.getId().toString(),
                            "sid", session.sid()
                    ));
                    return new AuthResponse(token, session.refreshToken(), jwtService.getExpirationMs() / 1000);
                })
        );

    } //check since this is a two wrapped response then, weneed to peel off the internal exceptions in authHandler

    
    public CompletableFuture<AuthResponse> refreshTokens(String presentedRt) {
        if (presentedRt == null || presentedRt.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Refresh token is required"));
        }
        int dotIndex = presentedRt.indexOf('.');
        if (dotIndex <= 0 || dotIndex == presentedRt.length() - 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Malformed refresh token"));
        }
        String sid = presentedRt.substring(0, dotIndex);

        // Step 1: Verify the session is still active in Redis (Layer 1 check)
        return sessionRepository.getSession(sid, IDLE_TTL).thenCompose(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Session expired or revoked"));
            }
            UUID userId = sessionOpt.get().userId();

            // Step 2: Offload Postgres user lookup to BLOCKING_EXECUTOR to verify user exists and get fresh email
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return userRepository.findById(userId)
                            .orElseThrow(() -> new IllegalArgumentException("User not found"));
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, BLOCKING_EXECUTOR).thenCompose(user -> {
                // Step 3: Speculatively generate the next credentials
                String newRt = sid + "." + UUID.randomUUID().toString().replace("-", "");
                String newAt = jwtService.createToken(user.getEmail(), Map.of(
                        "userId", user.getId().toString(),
                        "sid", sid
                ));

                // Step 4: Atomically rotate in Redis via Lua
                return sessionRepository.rotateRefreshToken(presentedRt, newRt, newAt, MAX_TTL, GRACE_TTL)
                        .thenCompose(rotation -> {
                            if (rotation.status() == RedisSessionRepository.RotationStatus.REPLAY_DETECTED) {
                                // REPLAY ATTACK DETECTED: Old token presented outside the 10s grace window!
                                // Immediately invalidate the entire session family!
                                return sessionRepository.deleteSession(sid).thenCompose(v ->
                                        CompletableFuture.failedFuture(new IllegalArgumentException("Session revoked due to token reuse violation"))
                                );
                            }

                            // SUCCESS or GRACE_HIT (both tabs synchronized without logout!)
                            long expiresInSec = jwtService.getExpirationMs() / 1000;
                            return CompletableFuture.completedFuture(
                                    new AuthResponse(rotation.accessToken(), rotation.refreshToken(), expiresInSec)
                            );
                        });
            });
        });
    }

    public CompletableFuture<User> authenticateRequest(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Bearer token required"));
        }
        String token = authHeader.substring(7).trim();
        // Layer 1: In-memory cryptographic verification (fast, nanoseconds, non-blocking)
        if (!jwtService.validateToken(token)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid or expired token"));
        }
        Claims claims = jwtService.parseToken(token);
        String sid = claims.get("sid", String.class);
        String userIdStr = claims.get("userId", String.class);
        if (sid == null || sid.isBlank() || userIdStr == null || userIdStr.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid token payload"));
        }
        UUID userId = UUID.fromString(userIdStr);
        // Layer 2: Async Redis check (slides idle TTL)
        return sessionRepository.getSession(sid, IDLE_TTL).thenCompose(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Session expired or revoked"));
            }
            // Step 3: Offload Postgres lookup to BLOCKING_EXECUTOR to protect Lettuce thread
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return userRepository.findById(userId)
                            .orElseThrow(() -> new IllegalArgumentException("User not found"));
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, BLOCKING_EXECUTOR);
        });
    }

     public CompletableFuture<Void> logout(String token) {
        Claims claims = jwtService.parseToken(token);
        String sid = claims.get("sid", String.class);
        if (sid == null || sid.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return sessionRepository.deleteSession(sid);
    }

    public CompletableFuture<Void> revokeAll(UUID userId) {
        return sessionRepository.revokeAllUserSessions(userId);
    }

}


//THE SUPPLY AND THEN ASYNC PROBLEM



//OLDER Netty Blocking way of the Direct Argon and Pool directly on Netty NIO
/*
public CompletableFuture<AuthResponse> login(LoginRequest req) throws SQLException {
        // 1. Fetch user by email
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User Not Available"));

        // 2. Verify password
        if (!passwordService.verify(user.getPasswordHash(), req.getPassword())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        // 3. Issue JWT (OLD POSTGRES ONLY MEHTOD)
        //String token = jwtService.createToken(user.getEmail(), Map.of("userId", user.getId().toString()));
        //return new AuthResponse(token, jwtService.getExpirationMs() / 1000);
        
         return sessionRepository.createSession(user.getId(), IDLE_TTL, MAX_TTL)
                .thenApply(session -> {
                    String token = jwtService.createToken(user.getEmail(), Map.of(
                            "userId", user.getId().toString(),
                            "sid", session.sid()
                    ));
                    return new AuthResponse(token, jwtService.getExpirationMs() / 1000);
                });
    }
*/