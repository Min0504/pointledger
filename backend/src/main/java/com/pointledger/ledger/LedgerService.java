package com.pointledger.ledger;

import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import com.pointledger.ledger.LotPlanner.PlannedConsumption;
import com.pointledger.ledger.LotPlanner.PlannedRestore;
import com.pointledger.ledger.LotPlanner.RestoreSource;
import com.pointledger.ledger.dto.LedgerDtos.CancelRequest;
import com.pointledger.ledger.dto.LedgerDtos.CancelResponse;
import com.pointledger.ledger.dto.LedgerDtos.ConsumedLotView;
import com.pointledger.ledger.dto.LedgerDtos.EarnRequest;
import com.pointledger.ledger.dto.LedgerDtos.EarnResponse;
import com.pointledger.ledger.dto.LedgerDtos.GraceLotView;
import com.pointledger.ledger.dto.LedgerDtos.GrantRequest;
import com.pointledger.ledger.dto.LedgerDtos.GrantResponse;
import com.pointledger.ledger.dto.LedgerDtos.LedgerEntryView;
import com.pointledger.ledger.dto.LedgerDtos.LedgerPageResponse;
import com.pointledger.ledger.dto.LedgerDtos.RedeemRequest;
import com.pointledger.ledger.dto.LedgerDtos.RedeemResponse;
import com.pointledger.ledger.dto.LedgerDtos.RevokeRequest;
import com.pointledger.ledger.dto.LedgerDtos.RevokeResponse;
import com.pointledger.wallet.Wallet;
import com.pointledger.wallet.WalletRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 포인트 변화의 유일한 관문. 잔액(wallet.balance)을 갱신하는 코드는
 * 이 서비스 밖에 존재하지 않으며, 갱신은 반드시 원장 INSERT와 같은 트랜잭션이다 —
 * "원장이 진실, 잔액은 파생"을 코드 구조로 강제한다 (기획서 §4).
 *
 * 쓰기 5경로(earn/redeem/cancel/grant/revoke) 전부가 지갑 행 락(FOR UPDATE)으로
 * 시작한다. 락 규약이 한 곳이라도 빠지면 lost update가 되살아난다 — 적립조차
 * dirty checking의 절대값 UPDATE라 경합 대상이다 (재현: RedeemConcurrencyTest).
 * 만료 배치(Phase 5)도 같은 규약을 따르므로 온라인 요청과 경합해도 정합성이 유지된다.
 *
 * 로트 정합성: 잔액을 줄이는 경로(redeem/revoke)는 로트를 FIFO로 차감하고
 * 소비 기록(lot_consumptions)을 남기며, 늘리는 경로(earn/grant/cancel)는 로트를
 * 만들거나 복원한다 — 따라서 SUM(lot.remaining) == wallet.balance가 항상 성립한다
 * (검증: LedgerInvariantPropertyTest).
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PointLotRepository pointLotRepository;
    private final LotConsumptionRepository lotConsumptionRepository;

    /** 적립 — 원장 EARN + 로트 생성 + 잔액 반영이 한 트랜잭션 */
    @Transactional
    public EarnResponse earn(EarnRequest req, String createdBy, String idempotencyKey) {
        Credit credit = credit(req.userId(), LedgerEntryType.EARN, req.amount(),
                req.refType(), req.refId(), null, req.expireDays(), createdBy, idempotencyKey);
        return new EarnResponse(
                credit.entry().getId(), credit.lot().getId(), credit.balanceAfter(),
                credit.lot().getExpiresAt());
    }

    /** 운영자 수동 지급 — 적립과 같은 경로, 사유 필수(스키마 CHECK가 최후 방어) */
    @Transactional
    public GrantResponse grant(GrantRequest req, String createdBy, String idempotencyKey) {
        Credit credit = credit(req.userId(), LedgerEntryType.ADMIN_GRANT, req.amount(),
                "CS", null, req.reason(), req.expireDays(), createdBy, idempotencyKey);
        return new GrantResponse(
                credit.entry().getId(), credit.lot().getId(), credit.balanceAfter(),
                credit.lot().getExpiresAt());
    }

    /** 사용 — 지갑 행 락 → 잔액 검증 → 로트 FIFO 차감 → 원장 REDEEM → 잔액 차감 */
    @Transactional
    public RedeemResponse redeem(RedeemRequest req, String createdBy, String idempotencyKey) {
        Debit debit = debit(req.userId(), LedgerEntryType.REDEEM, req.amount(),
                req.refType(), req.refId(), null, createdBy, idempotencyKey);
        return new RedeemResponse(debit.entry().getId(), debit.balanceAfter(), debit.consumedLots());
    }

    /** 운영자 수동 회수 — 사용과 같은 차감 경로, 사유 필수 */
    @Transactional
    public RevokeResponse revoke(RevokeRequest req, String createdBy, String idempotencyKey) {
        Debit debit = debit(req.userId(), LedgerEntryType.ADMIN_REVOKE, req.amount(),
                "CS", null, req.reason(), createdBy, idempotencyKey);
        return new RevokeResponse(debit.entry().getId(), debit.balanceAfter(), debit.consumedLots());
    }

    /**
     * 사용 취소 — 원장 CANCEL + 소비의 역순 복원 + 잔액 복구.
     *
     * 복원 규칙 (LotPlanner에 단위 테스트로 문서화):
     *   - 소비의 역순(늦게 만료되는 로트부터) — 복원분이 가장 오래 살아남는다
     *   - 원 로트가 만료됐으면 유예 로트(취소 시점 + 7일)로 — 복원 직후 소멸 방지
     *   - 부분 취소 누적은 lot_consumptions.restored가 추적, 초과는 409
     *
     * 원장은 append-only이므로 취소도 "REDEEM 삭제"가 아니라 반대 방향 CANCEL
     * 추가다. relatedEntryId가 어느 사용을 되돌렸는지 감사 연결을 남긴다.
     */
    @Transactional
    public CancelResponse cancel(
            Long redeemEntryId, CancelRequest req, String createdBy, String idempotencyKey) {
        LedgerEntry redeemEntry = ledgerEntryRepository.findById(redeemEntryId)
                .orElseThrow(() -> new DomainException(ErrorCode.ENTRY_NOT_FOUND));
        if (redeemEntry.getType() != LedgerEntryType.REDEEM) {
            throw new DomainException(ErrorCode.NOT_CANCELLABLE,
                    Map.of("entryType", redeemEntry.getType().name()));
        }

        // 지갑 락을 잡은 뒤에 소비 기록을 읽어야 동시 취소가 직렬화된다 —
        // 두 번째 취소는 첫 취소가 커밋한 restored를 보고 잔여를 판정한다
        Wallet wallet = walletRepository.findByIdForUpdate(redeemEntry.getWalletId())
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));

        List<LotConsumption> consumptions =
                lotConsumptionRepository.findByConsumingEntryIdOrderById(redeemEntryId);
        long cancellable = consumptions.stream().mapToLong(LotConsumption::restorable).sum();
        long amount = req.amount() != null ? req.amount() : cancellable;
        // amount == 0은 이미 전액 취소된 사용의 재취소 — 원장 CHECK(amount > 0)에
        // 닿기 전에 도메인 답(409)으로 끊는다
        if (amount == 0 || amount > cancellable) {
            throw new DomainException(ErrorCode.CANCEL_EXCEEDS_REMAINING, Map.of(
                    "cancellable", cancellable, "requested", amount));
        }

        // 로트 일괄 조회(IN) 후 소비 순서대로 짝짓기 — 소비 건수만큼 쿼리하지 않는다
        Map<Long, PointLot> lotById = pointLotRepository.findAllById(
                        consumptions.stream().map(LotConsumption::getLotId).toList())
                .stream().collect(Collectors.toMap(PointLot::getId, Function.identity()));
        List<RestoreSource> sources = consumptions.stream()
                .map(c -> new RestoreSource(c, lotById.get(c.getLotId())))
                .toList();

        Instant now = Instant.now();
        List<PlannedRestore> plan = LotPlanner.planRestore(sources, amount, now);

        long balanceAfter = wallet.getBalance() + amount;
        LedgerEntry cancelEntry = ledgerEntryRepository.save(LedgerEntry.builder()
                .walletId(wallet.getId())
                .type(LedgerEntryType.CANCEL)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .refType(redeemEntry.getRefType())
                .refId(redeemEntry.getRefId())
                .relatedEntryId(redeemEntryId)
                .idempotencyKey(idempotencyKey)
                .createdBy(createdBy)
                .build());

        long graceAmount = 0;
        List<ConsumedLotView> restoredLots = new ArrayList<>();
        for (PlannedRestore restore : plan) {
            restore.source().consumption().markRestored(restore.amount());
            if (restore.toGraceLot()) {
                graceAmount += restore.amount();
            } else {
                restore.source().lot().restore(restore.amount());
                restoredLots.add(new ConsumedLotView(
                        restore.source().lot().getId(), restore.amount()));
            }
        }

        GraceLotView graceLot = null;
        if (graceAmount > 0) {
            PointLot grace = pointLotRepository.save(new PointLot(
                    wallet.getId(), cancelEntry.getId(), graceAmount,
                    now.plus(Duration.ofDays(LotPlanner.GRACE_DAYS))));
            graceLot = new GraceLotView(grace.getId(), graceAmount, grace.getExpiresAt());
        }

        wallet.apply(LedgerEntryType.CANCEL.signed(amount));
        return new CancelResponse(cancelEntry.getId(), balanceAfter, restoredLots, graceLot);
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

    // ── 공통 내부 경로 — 잔액을 늘리는(credit) / 줄이는(debit) 두 갈래뿐 ──────

    private record Credit(LedgerEntry entry, PointLot lot, long balanceAfter) {
    }

    private record Debit(LedgerEntry entry, List<ConsumedLotView> consumedLots, long balanceAfter) {
    }

    /**
     * 적립 계열(EARN/ADMIN_GRANT) — 원장 + 새 로트 + 잔액 반영.
     *
     * 적립도 지갑 행 락을 잡는다: dirty checking의 잔액 갱신은 델타(+1000)가
     * 아니라 절대값(SET balance = 읽은값+1000)이라, 증가만 하는 연산이어도
     * 동시 적립끼리 lost update가 난다 (재현에서 20건 중 16건 소실).
     */
    private Credit credit(Long userId, LedgerEntryType type, long amount,
            String refType, String refId, String reason, int expireDays,
            String createdBy, String idempotencyKey) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));

        long balanceAfter = wallet.getBalance() + amount;
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.builder()
                .walletId(wallet.getId())
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .refType(refType)
                .refId(refId)
                .reason(reason)
                .idempotencyKey(idempotencyKey)
                .createdBy(createdBy)
                .build());

        Instant expiresAt = Instant.now().plus(Duration.ofDays(expireDays));
        PointLot lot = pointLotRepository.save(
                new PointLot(wallet.getId(), entry.getId(), amount, expiresAt));

        wallet.apply(type.signed(amount));
        return new Credit(entry, lot, balanceAfter);
    }

    /**
     * 차감 계열(REDEEM/ADMIN_REVOKE) — 락 → 잔액 검증 → FIFO 차감 → 원장 + 잔액.
     *
     * FOR UPDATE가 "읽고 → 검사하고 → 갱신"을 지갑 단위로 직렬화한다. 트랜잭션
     * 안에 외부 호출이 없어야 락 보유 시간이 짧게 유지된다 — PG 연동 같은
     * 외부 I/O가 생기면 반드시 트랜잭션 밖으로 뺀다.
     */
    private Debit debit(Long userId, LedgerEntryType type, long amount,
            String refType, String refId, String reason,
            String createdBy, String idempotencyKey) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND));

        if (wallet.getBalance() < amount) {
            throw new DomainException(ErrorCode.INSUFFICIENT_BALANCE, Map.of(
                    "balance", wallet.getBalance(),
                    "requested", amount,
                    "shortage", amount - wallet.getBalance()));
        }

        long balanceAfter = wallet.getBalance() - amount;
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.builder()
                .walletId(wallet.getId())
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .refType(refType)
                .refId(refId)
                .reason(reason)
                .idempotencyKey(idempotencyKey)
                .createdBy(createdBy)
                .build());

        List<PlannedConsumption> plan = LotPlanner.planConsumption(
                pointLotRepository.findFifoConsumable(wallet.getId()), amount);
        List<ConsumedLotView> consumedLots = new ArrayList<>();
        for (PlannedConsumption consumption : plan) {
            consumption.lot().consume(consumption.amount());
            lotConsumptionRepository.save(new LotConsumption(
                    entry.getId(), consumption.lot().getId(), consumption.amount()));
            consumedLots.add(new ConsumedLotView(consumption.lot().getId(), consumption.amount()));
        }

        wallet.apply(type.signed(amount));
        return new Debit(entry, consumedLots, balanceAfter);
    }
}
