package com.pointledger.reconcile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대사 불일치 기록 (기획서 §5, 문제 5). 탐지는 자동, 수정은 사람 —
 * 돈 관련 자동 보정은 버그가 버그를 덮는 경로다. 해결(resolve)은 검토
 * 완료 표시일 뿐이고, 실제 정정은 ADMIN_GRANT/REVOKE + 사유로만 한다.
 */
@Entity
@Table(name = "reconcile_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconcileIssue {

    public enum Type { SNAPSHOT_MISMATCH, EXTERNAL_MISSING, INTERNAL_MISSING, SETTLEMENT_MISMATCH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_run_id", nullable = false)
    private Long jobRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 30)
    private Type issueType;

    @Column(name = "wallet_id")
    private Long walletId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "ref_id", length = 64)
    private String refId;

    private Long expected;

    private Long actual;

    @Column(nullable = false)
    private boolean resolved;

    @Column(length = 500)
    private String memo;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public void resolve(String operator, String memo) {
        this.resolved = true;
        this.resolvedBy = operator;
        this.memo = memo;
        // 저장 정밀도(마이크로초)로 절단 — 응답과 이후 조회가 같은 값이어야 한다
        this.resolvedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
