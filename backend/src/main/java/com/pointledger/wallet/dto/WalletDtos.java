package com.pointledger.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class WalletDtos {

    private WalletDtos() {
    }

    public record CreateWalletRequest(@NotNull @Positive Long userId) {
    }

    public record WalletResponse(Long walletId, Long userId, long balance) {
    }

    /** expiringSoon: 30일 내 만료 예정 잔여 포인트 합 */
    public record BalanceResponse(Long walletId, Long userId, long balance, long expiringSoon) {
    }
}
