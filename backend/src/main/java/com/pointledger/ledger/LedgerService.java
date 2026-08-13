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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 모든 포인트 변화의 유일한 관문. 잔액(wallet.balance)을 갱신하는 코드는
 * 이 서비스 밖에 존재하지 않으며, 갱신은 반드시 원장 INSERT와 같은 트랜잭션이다 —
 * "원장이 진실, 잔액은 파생"을 코드 구조로 강제한다 (기획서 §4).
 *
 * [실험 브랜치: 낙관적 락 + 재시도]
 * 비관적 락(main) 대신 @Version 충돌 감지로 lost update를 막는다. 시도 단위로
 * 트랜잭션을 새로 열어야 하므로(실패한 영속성 컨텍스트는 재사용 불가) 재시도
 * 루프가 트랜잭션 밖에 있고, 그래서 @Transactional 대신 TransactionTemplate을
 * 쓴다 — 이 구조 복잡도 자체가 다단계 쓰기에서 낙관적 락이 갖는 비용이다.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    /** 경합 시 재시도 상한 — 소진되면 호출자에게 409로 위임 (백오프 없는 즉시 재시도) */
    private static final int MAX_ATTEMPTS = 3;

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PointLotRepository pointLotRepository;
    private final TransactionTemplate tx;

    /**
     * 적립 — 원장 EARN + 로트 생성 + 잔액 반영이 시도당 한 트랜잭션.
     * 증가만 하는 연산이어도 dirty checking은 절대값 UPDATE라 경합 대상이고,
     * 낙관적 전략에서는 적립끼리도 버전 충돌로 재시도를 소모한다 — 비관적 락이면
     * 그냥 줄을 서는 상황이 여기서는 실패 가능성이 된다.
     *
     * [Phase 1 한정] 아직 멱등성 키를 받지 않는다 — 재시도 이중 적립 문제와
     * 해결(요청 상태 테이블)은 Phase 3에서 재현 테스트와 함께 들어온다.
     */
    public EarnResponse earn(EarnRequest req, String createdBy) {
        return withOptimisticRetry(() -> tx.execute(status -> doEarn(req, createdBy)));
    }

    private EarnResponse doEarn(EarnRequest req, String createdBy) {
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
     * 사용 — 읽기 → 잔액 검증 → 원장 REDEEM → 커밋 시 버전 검사.
     * 읽기와 커밋 사이에 다른 트랜잭션이 지갑을 고쳤다면 UPDATE의 WHERE version이
     * 0건을 갱신하고 예외가 난다 → 시도 전체 롤백(원장 INSERT 포함) 후 재시도.
     *
     * [Phase 1 한정] 어느 적립분에서 나갔는지(FIFO)는 아직 기록하지 않는다 —
     * 사용 취소·만료 정확성이 로트 소비 기록을 요구하는 시점(Phase 4)에
     * lot_consumptions와 함께 들어온다.
     */
    public RedeemResponse redeem(RedeemRequest req, String createdBy) {
        return withOptimisticRetry(() -> tx.execute(status -> doRedeem(req, createdBy)));
    }

    private RedeemResponse doRedeem(RedeemRequest req, String createdBy) {
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

    /**
     * 버전 충돌 시 즉시 재시도, 상한 소진 시 409. 도메인 예외(잔액 부족 등)는
     * 재시도 대상이 아니다 — 다시 읽어도 같은 결론이므로 그대로 전파한다.
     */
    private <T> T withOptimisticRetry(java.util.function.Supplier<T> attempt) {
        for (int i = 1; ; i++) {
            try {
                return attempt.get();
            } catch (OptimisticLockingFailureException e) {
                if (i == MAX_ATTEMPTS) {
                    throw new DomainException(ErrorCode.CONFLICT_RETRY_EXHAUSTED);
                }
            }
        }
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
