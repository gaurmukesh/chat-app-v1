package com.mg.chat_app.service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.mg.chat_app.dto.TokenResponse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String REFRESH_TOKEN_KEY_PREFIX = "refresh_token:";

    private final Key key;
    private final long accessExpiryMs;
    private final long refreshExpiryMs;
    private final RedisTemplate<String, Object> redisTemplate;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiry-ms:900000}") long accessExpiryMs,
            @Value("${jwt.refresh-expiry-ms:604800000}") long refreshExpiryMs,
            RedisTemplate<String, Object> redisTemplate) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessExpiryMs = accessExpiryMs;
        this.refreshExpiryMs = refreshExpiryMs;
        this.redisTemplate = redisTemplate;
    }

    public String generateAccessToken(String userId) {
        return Jwts.builder()
                .setSubject(userId)
                .claim("tokenType", "access")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessExpiryMs))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(String userId) {
        return Jwts.builder()
                .setSubject(userId)
                .claim("tokenType", "refresh")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshExpiryMs))
                .signWith(key)
                .compact();
    }

    public void storeRefreshToken(String userId, String refreshToken) {
        String hash = sha256(refreshToken);
        String redisKey = REFRESH_TOKEN_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(redisKey, hash, refreshExpiryMs, TimeUnit.MILLISECONDS);
    }

    public TokenResponse rotateRefreshToken(String oldToken) {
        String userId = validateRefreshToken(oldToken);

        String redisKey = REFRESH_TOKEN_KEY_PREFIX + userId;
        Object storedHash = redisTemplate.opsForValue().get(redisKey);
        String oldHash = sha256(oldToken);

        if (storedHash == null || !oldHash.equals(storedHash.toString())) {
            // Token already rotated — possible theft. Revoke to be safe.
            redisTemplate.delete(redisKey);
            throw new JwtException("Refresh token already used or revoked");
        }

        // Delete old, issue new pair
        redisTemplate.delete(redisKey);
        String newAccessToken = generateAccessToken(userId);
        String newRefreshToken = generateRefreshToken(userId);
        storeRefreshToken(userId, newRefreshToken);

        return new TokenResponse(newAccessToken, newRefreshToken);
    }

    public void revokeRefreshToken(String userId) {
        redisTemplate.delete(REFRESH_TOKEN_KEY_PREFIX + userId);
    }

    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
    }

    public String validateAccessToken(String token) {
        Claims claims = parseClaims(token);
        String tokenType = claims.get("tokenType", String.class);
        if (!"access".equals(tokenType)) {
            throw new JwtException("Not an access token");
        }
        return claims.getSubject();
    }

    public String validateRefreshToken(String token) {
        Claims claims = parseClaims(token);
        String tokenType = claims.get("tokenType", String.class);
        if (!"refresh".equals(tokenType)) {
            throw new JwtException("Not a refresh token");
        }
        return claims.getSubject();
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            throw new JwtException("JWT expired", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
