package com.pointledger.wallet;

import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import com.pointledger.ledger.PointLotRepository;
import com.pointledger.wallet.dto.WalletDtos.BalanceResponse;
import com.pointledger.wallet.dto.WalletDtos.WalletResponse;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletService {

    /** 만료 예정 집계 구간 — 알림 데이터 요구사항 기준 30일 (기획서 §2) */
    private static final Duration EXPIRING_WINDOW = Duration.ofDays(30);

    private final WalletRepository walletRepository;
    private final PointLotRepository pointLotRepository;

    @Transactional
    public WalletResponse create(Long userId) {
        try {
            Wallet saved = walletRepository.save(new Wallet(userId));
            return new WalletResponse(saved.getId(), saved.getUserId(), saved.getBalance());
        } catch (DataIntegrityViolationException e) {
            // user_id UNIQUE 충돌 — 사전 SELECT로 검사하면 동시 생성 race가 남으므로
            // 제약 위반을 정상 분기로 다룬다 (검사와 삽입을 원자화)
            throw new DomainException(ErrorCode.WALLET_ALREADY_EXISTS);
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));
        Instant now = Instant.now();
        long expiringSoon = pointLotRepository.sumExpiringBetween(
                wallet.getId(), now, now.plus(EXPIRING_WINDOW));
        return new BalanceResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance(), expiringSoon);
    }
}
