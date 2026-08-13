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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 서버 간 인증 키. 원문은 발급 응답에서 한 번만 노출하고 SHA-256 해시만 저장한다 —
 * DB가 유출돼도 키 원문은 복원 불가 (기획서 §9).
 */
@Entity
@Table(name = "api_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 호출 주체 식별용 — 원장 created_by에 이 이름이 기록된다 (감사 추적) */
    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public ApiKey(String name, String keyHash) {
        this.name = name;
        this.keyHash = keyHash;
        this.active = true;
    }
}
