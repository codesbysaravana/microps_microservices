package com.auth.service;

import com.auth.dto.AuthResponse;
import com.auth.dto.LoginRequest;

import java.util.Map;

public class AuthService {

    private final PasswordService passwordService;
    private final JwtService jwtService;

    public AuthService() {
        this(new PasswordService(), new JwtService());
    }

    public AuthService(PasswordService passwordService, JwtService jwtService) {
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    public boolean authenticate(LoginRequest loginRequest, String storedPasswordHash) {
        if (loginRequest == null || loginRequest.getEmail() == null || loginRequest.getPassword() == null) {
            return false;
        }
        if (storedPasswordHash == null || storedPasswordHash.isEmpty()) {
            return false;
        }
        return passwordService.verify(storedPasswordHash, loginRequest.getPassword());
    }

    public AuthResponse login(LoginRequest loginRequest, String storedPasswordHash) {
        if (!authenticate(loginRequest, storedPasswordHash)) {
            return null;
        }

        String token = jwtService.createToken(loginRequest.getEmail(), Map.of("email", loginRequest.getEmail()));
        return new AuthResponse(token, jwtService.getExpirationMs() / 1000);
    }

    public String hashPassword(String plainPassword) {
        return passwordService.hash(plainPassword);
    }

    public PasswordService getPasswordService() {
        return passwordService;
    }

    public JwtService getJwtService() {
        return jwtService;
    }
}
