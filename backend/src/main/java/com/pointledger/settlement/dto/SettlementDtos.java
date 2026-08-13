package com.pointledger.settlement.dto;

import com.pointledger.settlement.Merchant;
import com.pointledger.settlement.Settlement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SettlementDtos {

    private SettlementDtos() {
    }

    public record MerchantCreateRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record MerchantView(Long id, String name, String status, Instant createdAt) {

        public static MerchantView from(Merchant m) {
            return new MerchantView(m.getId(), m.getName(), m.getStatus().name(), m.getCreatedAt());
        }
    }

    public record SettlementView(
            Long id, Long merchantId, String merchantName, LocalDate settleDate,
            long totalAmount, String status, String confirmedBy, Instant confirmedAt) {

        public static SettlementView from(Settlement s, String merchantName) {
            return new SettlementView(s.getId(), s.getMerchantId(), merchantName,
                    s.getSettleDate(), s.getTotalAmount(), s.getStatus().name(),
                    s.getConfirmedBy(), s.getConfirmedAt());
        }
    }

    public record SettlementListResponse(List<SettlementView> items) {
    }
}
