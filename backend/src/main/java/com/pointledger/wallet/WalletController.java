package com.pointledger.wallet;

import com.pointledger.ledger.LedgerService;
import com.pointledger.ledger.dto.LedgerDtos.LedgerPageResponse;
import com.pointledger.wallet.dto.WalletDtos.BalanceResponse;
import com.pointledger.wallet.dto.WalletDtos.CreateWalletRequest;
import com.pointledger.wallet.dto.WalletDtos.WalletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 서버 간 API (X-API-Key) — 주문 서버가 호출하는 지갑 조회 계열 */
@RestController
@Validated
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final LedgerService ledgerService;

    @PostMapping("/wallets")
    @ResponseStatus(HttpStatus.CREATED)
    public WalletResponse create(@Valid @RequestBody CreateWalletRequest request) {
        return walletService.create(request.userId());
    }

    @GetMapping("/wallets/{userId}/balance")
    public BalanceResponse balance(@PathVariable Long userId) {
        return walletService.balance(userId);
    }

    @GetMapping("/wallets/{userId}/ledger")
    public LedgerPageResponse ledger(
            @PathVariable Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ledgerService.ledger(userId, cursor, size);
    }
}
