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
     * 적립도 지갑 행 락을 잡는다: dirty checking의 잔액 갱신은 델타(+1000)가 아니라
     * 절대값(SET balance = 읽은값+1000)이라, 증가만 하는 연산이어도 동시 적립끼리
     * lost update가 난다 (재현 테스트에서 20건 중 16건 소실). 쓰기 경로 전체가
     * 같은 락 규약을 따라야 만료 배치(Phase 5)와의 경합에서도 정합성이 유지된다.
     *
     * 멱등성 키는 원장 행에도 남긴다 — 요청 상태 테이블(1차 방어)을 우회하는
     * 코드 경로가 생겨도 partial unique index가 이중 기장을 DB에서 거부한다(2차 방어).
     */
    @Transactional
    public EarnResponse earn(EarnRequest req, String createdBy, String idempotencyKey) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(req.userId())
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));

        long balanceAfter = wallet.getBalance() + req.amount();
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.builder()
                .walletId(wallet.getId())
                .type(LedgerEntryType.EARN)
                .amount(req.amount())
                .balanceAfter(balanceAfter)
                .refType(req.refType())
                .refId(req.refId())
                .idempotencyKey(idempotencyKey)
                .createdBy(createdBy)
                .build());

        Instant expiresAt = Instant.now().plus(Duration.ofDays(req.expireDays()));
        PointLot lot = pointLotRepository.save(
                new PointLot(wallet.getId(), entry.getId(), req.amount(), expiresAt));

        wallet.apply(LedgerEntryType.EARN.signed(req.amount()));
        return new EarnResponse(entry.getId(), lot.getId(), balanceAfter, expiresAt);
    }

    /**
     * 사용 — 지갑 행 락 → 잔액 검증 → 원장 REDEEM → 잔액 차감.
     *
     * FOR UPDATE가 "읽고 → 검사하고 → 갱신"을 지갑 단위로 직렬화한다. 락 없이는
     * READ COMMITTED에서 두 트랜잭션이 같은 잔액을 읽어 lost update가 났다
     * (재현: RedeemConcurrencyTest — 이 파일의 이전 커밋에서 실패 상태로 보존).
     * 락 선택 이유와 대안 비교는 WalletRepository.findByUserIdForUpdate 주석 참조.
     * 트랜잭션 안에 외부 호출이 없어야 락 보유 시간이 짧게 유지된다 — PG 연동
     * 같은 외부 I/O가 생기면 반드시 트랜잭션 밖으로 뺀다.
     *
     * [Phase 1 한정] 어느 적립분에서 나갔는지(FIFO)는 아직 기록하지 않는다 —
     * 사용 취소·만료 정확성이 로트 소비 기록을 요구하는 시점(Phase 4)에
     * lot_consumptions와 함께 들어온다.
     */
    @Transactional
    public RedeemResponse redeem(RedeemRequest req, String createdBy, String idempotencyKey) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(req.userId())
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
                .idempotencyKey(idempotencyKey)
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
