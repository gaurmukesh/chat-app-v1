package com.mg.chat_app.controller;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mg.chat_app.dto.LoginRequest;
import com.mg.chat_app.dto.RefreshTokenRequest;
import com.mg.chat_app.dto.TokenResponse;
import com.mg.chat_app.entity.User;
import com.mg.chat_app.repository.UserRepository;
import com.mg.chat_app.service.JwtService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public TokenResponse register(@Valid @RequestBody LoginRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .createdAt(LocalDateTime.now())
                .build();
        user = userRepository.save(user);

        String userId = user.getUserId().toString();
        String accessToken = jwtService.generateAccessToken(userId);
        String refreshToken = jwtService.generateRefreshToken(userId);
        jwtService.storeRefreshToken(userId, refreshToken);
        return new TokenResponse(accessToken, refreshToken);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String userId = user.getUserId().toString();
        String accessToken = jwtService.generateAccessToken(userId);
        String refreshToken = jwtService.generateRefreshToken(userId);
        jwtService.storeRefreshToken(userId, refreshToken);
        return new TokenResponse(accessToken, refreshToken);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return jwtService.rotateRefreshToken(request.getRefreshToken());
    }

    @PostMapping("/logout")
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        String userId = jwtService.extractUserId(request.getRefreshToken());
        jwtService.revokeRefreshToken(userId);
    }
}
