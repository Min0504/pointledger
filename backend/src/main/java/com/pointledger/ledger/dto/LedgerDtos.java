package com.pointledger.ledger.dto;

import com.pointledger.ledger.LedgerEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class LedgerDtos {

    private LedgerDtos() {
    }

    public record EarnRequest(
            @NotNull Long userId,
            @NotNull @Positive Long amount,
            @NotBlank @Size(max = 20) String refType,
            @NotBlank @Size(max = 64) String refId,
            // 만료일은 호출자(프로모션마다 다름)가 정하되 1~3650일로 제한
            @NotNull @Min(1) @Max(3650) Integer expireDays) {
    }

    public record EarnResponse(Long entryId, Long lotId, long balanceAfter, Instant expiresAt) {
    }

    public record RedeemRequest(
            @NotNull Long userId,
            @NotNull @Positive Long amount,
            @NotBlank @Size(max = 20) String refType,
            @NotBlank @Size(max = 64) String refId) {
    }

    public record RedeemResponse(Long entryId, long balanceAfter) {
    }

    public record LedgerEntryView(
            Long id, String type, long amount, long balanceAfter,
            String refType, String refId, String reason, String createdBy, Instant createdAt) {

        public static LedgerEntryView from(LedgerEntry e) {
            return new LedgerEntryView(e.getId(), e.getType().name(), e.getAmount(),
                    e.getBalanceAfter(), e.getRefType(), e.getRefId(), e.getReason(),
                    e.getCreatedBy(), e.getCreatedAt());
        }
    }

    /** nextCursor가 null이면 마지막 페이지 */
    public record LedgerPageResponse(List<LedgerEntryView> items, Long nextCursor) {
    }
}
