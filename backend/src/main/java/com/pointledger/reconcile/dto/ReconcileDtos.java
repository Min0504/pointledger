package com.pointledger.reconcile.dto;

import com.pointledger.reconcile.ReconcileIssue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ReconcileDtos {

    private ReconcileDtos() {
    }

    public record IssueView(
            Long id, Long jobRunId, String issueType, Long walletId, Long merchantId,
            String refId, Long expected, Long actual, boolean resolved,
            String memo, String resolvedBy, Instant resolvedAt, Instant createdAt) {

        public static IssueView from(ReconcileIssue i) {
            return new IssueView(i.getId(), i.getJobRunId(), i.getIssueType().name(),
                    i.getWalletId(), i.getMerchantId(), i.getRefId(), i.getExpected(),
                    i.getActual(), i.isResolved(), i.getMemo(), i.getResolvedBy(),
                    i.getResolvedAt(), i.getCreatedAt());
        }
    }

    public record IssueListResponse(List<IssueView> items) {
    }

    /** 처리 근거는 필수 — "확인했음" 없는 이슈 닫기는 감사 관점에서 무의미하다 */
    public record ResolveRequest(@NotBlank @Size(max = 500) String memo) {
    }
}
