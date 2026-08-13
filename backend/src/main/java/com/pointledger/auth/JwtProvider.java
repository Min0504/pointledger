package com.pointledger.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 운영자 토큰 발급/검증. 서버 간 호출은 X-API-Key를 쓰므로 JWT는 백오피스 전용이다.
 * refresh 없이 1시간 단기 토큰 — 내부 도구는 재로그인이 rotation 관리보다 단순하다.
 */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final Duration ttl;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.ttl-sec}") long ttlSec) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofSeconds(ttlSec);
    }

    public String issue(Operator operator) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(operator.getId()))
                .claim("email", operator.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 서명·만료가 유효하면 클레임을, 아니면 empty — 필터에서 401 처리 */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
