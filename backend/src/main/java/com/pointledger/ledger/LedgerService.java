package com.pointledger.ledger;

import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import com.pointledger.ledger.dto.LedgerDtos.EarnRequest;
import com.pointledger.ledger.dto.LedgerDtos.EarnResponse;
import com.pointledger.ledger.dto.LedgerDtos.LedgerEntryView;
import com.pointledger.ledger.dto.LedgerDtos.LedgerPageResponse;
import com.pointledger.ledger.dto.LedgerDtos.RedeemRequest;
import com.pointledger.ledger.dto.LedgerDtos.RedeemResponse;
import com.pointledger.wallet.Wallet;
import com.pointledger.wallet.WalletRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 포인트 변화의 유일한 관문. 잔액(wallet.balance)을 갱신하는 코드는
 * 이 서비스 밖에 존재하지 않으며, 갱신은 반드시 원장 INSERT와 같은 트랜잭션이다 —
 * "원장이 진실, 잔액은 파생"을 코드 구조로 강제한다 (기획서 §4).
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PointLotRepository pointLotRepository;

    /**
     * 적립 — 원장 EARN + 로트 생성 + 잔액 반영이 한 트랜잭션.
     *
     * [Phase 1 한정] 아직 멱등성 키를 받지 않는다 — 재시도 이중 적립 문제와
     * 해결(요청 상태 테이블)은 Phase 3에서 재현 테스트와 함께 들어온다.
     */
    @Transactional
    public EarnResponse earn(EarnRequest req, String createdBy) {
        Wallet wallet = walletRepository.findByUserId(req.userId())
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));

        long balanceAfter = wallet.getBalance() + req.amount();
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.builder()
                .walletId(wallet.getId())
                .type(LedgerEntryType.EARN)
                .amount(req.amount())
                .balanceAfter(balanceAfter)
                .refType(req.refType())
                .refId(req.refId())
                .createdBy(createdBy)
                .build());

        Instant expiresAt = Instant.now().plus(Duration.ofDays(req.expireDays()));
        PointLot lot = pointLotRepository.save(
                new PointLot(wallet.getId(), entry.getId(), req.amount(), expiresAt));

        wallet.apply(LedgerEntryType.EARN.signed(req.amount()));
        return new EarnResponse(entry.getId(), lot.getId(), balanceAfter, expiresAt);
    }

    /**
     * 사용 — 잔액 검증 + 원장 REDEEM + 잔액 차감.
     *
     * [Phase 1 한정 — 알려진 한계 두 가지]
     * 1. 동시성: 아래 "읽고 → 검사하고 → 갱신"은 READ COMMITTED에서 두 트랜잭션이
     *    같은 잔액을 읽으면 lost update가 난다. Phase 2에서 이 버그를 테스트로
     *    재현한 뒤 지갑 행 비관적 락으로 직렬화한다 (재현 커밋 → 해결 커밋 쌍).
     *    그동안의 최후 방어선은 DB CHECK(balance >= 0)다.
     * 2. 로트: 어느 적립분에서 나갔는지(FIFO) 아직 기록하지 않는다 — 사용 취소·
     *    만료 정확성이 로트 소비 기록을 요구하는 시점(Phase 4)에 lot_consumptions와
     *    함께 들어온다.
     */
    @Transactional
    public RedeemResponse redeem(RedeemRequest req, String createdBy) {
        Wallet wallet = walletRepository.findByUserId(req.userId())
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));

        if (wallet.getBalance() < req.amount()) {
            throw new DomainException(ErrorCode.INSUFFICIENT_BALANCE, Map.of(
                    "balance", wallet.getBalance(),
                    "requested", req.amount(),
                    "shortage", req.amount() - wallet.getBalance()));
        }

        long balanceAfter = wallet.getBalance() - req.amount();
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.builder()
                .walletId(wallet.getId())
                .type(LedgerEntryType.REDEEM)
                .amount(req.amount())
                .balanceAfter(balanceAfter)
                .refType(req.refType())
                .refId(req.refId())
                .createdBy(createdBy)
                .build());

        wallet.apply(LedgerEntryType.REDEEM.signed(req.amount()));
        return new RedeemResponse(entry.getId(), balanceAfter);
    }

    @Transactional(readOnly = true)
    public LedgerPageResponse ledger(Long userId, Long cursor, int size) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));

        // size+1로 조회해 다음 페이지 존재를 추가 COUNT 없이 판정한다
        List<LedgerEntry> rows = ledgerEntryRepository.pageByWallet(
                wallet.getId(),
                cursor == null ? Long.MAX_VALUE : cursor,
                Pageable.ofSize(size + 1));

        boolean hasNext = rows.size() > size;
        List<LedgerEntryView> items = rows.stream()
                .limit(size)
                .map(LedgerEntryView::from)
                .toList();
        Long nextCursor = hasNext ? items.get(items.size() - 1).id() : null;
        return new LedgerPageResponse(items, nextCursor);
    }
}
