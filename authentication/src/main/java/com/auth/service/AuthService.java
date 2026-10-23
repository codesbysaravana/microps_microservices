package com.auth.service;

import java.sql.SQLException;
import java.util.Map;

import com.auth.domain.User;
import com.auth.dto.AuthResponse;
import com.auth.dto.LoginRequest;
import com.auth.infrastructure.db.repository.PostgresUserRepository;

public class AuthService {
    private final PostgresUserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    public AuthService() {
        this(new PostgresUserRepository(), new PasswordService(), new JwtService());
    }

    public AuthService(PostgresUserRepository userRepository, PasswordService passwordService, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    public AuthResponse login(LoginRequest req) throws SQLException {
        // 1. Fetch user by email
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User Not Available"));

        // 2. Verify password
        if (!passwordService.verify(user.getPasswordHash(), req.getPassword())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        // 3. Issue JWT
        String token = jwtService.createToken(user.getEmail(), Map.of("userId", user.getId().toString()));
        return new AuthResponse(token, jwtService.getExpirationMs() / 1000);
    }

    public AuthResponse signup(LoginRequest req) throws SQLException {
        if (req == null || req.getEmail() == null || req.getPassword() == null) {
            throw new IllegalArgumentException("Email and password are required");
        }

        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already in use");
        }

        String passwordHash = passwordService.hash(req.getPassword());

        String name = req.getEmail().split("@")[0];
        User user = userRepository.createUser(name, req.getEmail(), passwordHash);

        String token = jwtService.createToken(user.getEmail(), Map.of("userId", user.getId().toString()));
        return new AuthResponse(token, jwtService.getExpirationMs() / 1000);
    }
}
