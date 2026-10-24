package com.auth.service;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.auth.domain.User;
import com.auth.dto.AuthResponse;
import com.auth.dto.LoginRequest;
import com.auth.infrastructure.db.repository.PostgresUserRepository;
import com.auth.infrastructure.redis.repository.RedisSessionRepository;

import io.jsonwebtoken.Claims;

public class AuthService {
    private final PostgresUserRepository userRepository;
    private final RedisSessionRepository sessionRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    private static final Duration IDLE_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_TTL = Duration.ofDays(7);

    public AuthService() {
        this(new PostgresUserRepository(), new RedisSessionRepository(), new PasswordService(), new JwtService());
    }

    public AuthService(PostgresUserRepository userRepository, RedisSessionRepository sessionRepository, PasswordService passwordService, JwtService jwtService) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

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

    public CompletableFuture<AuthResponse> signup(LoginRequest req) throws SQLException {
        if (req == null || req.getEmail() == null || req.getPassword() == null) {
            throw new IllegalArgumentException("Email and password are required");
        }

        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already in use");
        }

        String passwordHash = passwordService.hash(req.getPassword());

        String name = req.getEmail().split("@")[0];
        User user = userRepository.createUser(name, req.getEmail(), passwordHash);

        return sessionRepository.createSession(user.getId(), IDLE_TTL, MAX_TTL)
                .thenApply(session -> {
                    String token = jwtService.createToken(user.getEmail(), Map.of(
                            "userId", user.getId().toString(),
                            "sid", session.sid()
                    ));
                    return new AuthResponse(token, jwtService.getExpirationMs() / 1000);
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
