package com.pointledger.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 백오피스 운영자. 내부 도구라 셀프 가입이 없다 — 최초 계정은 부팅 시 환경변수로
 * 생성(AdminBootstrap)하고, 이후 필요하면 운영자가 DB에서 직접 추가한다.
 */
@Entity
@Table(name = "operators")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public Operator(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
