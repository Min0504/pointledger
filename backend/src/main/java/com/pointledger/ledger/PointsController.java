package com.pointledger.ledger;

import com.pointledger.idempotency.IdempotencyManager;
import com.pointledger.ledger.dto.LedgerDtos.CancelRequest;
import com.pointledger.ledger.dto.LedgerDtos.CancelResponse;
import com.pointledger.ledger.dto.LedgerDtos.EarnRequest;
import com.pointledger.ledger.dto.LedgerDtos.EarnResponse;
import com.pointledger.ledger.dto.LedgerDtos.RedeemRequest;
import com.pointledger.ledger.dto.LedgerDtos.RedeemResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버 간 API (X-API-Key) — 적립/사용. 호출 주체(키 이름)가 원장 created_by로 남는다.
 * 변경 계열은 Idempotency-Key 필수 — 네트워크는 "처리됐는지"를 알려주지 않으므로
 * at-least-once 재시도는 호출자의 합리적 행동이고, 중복 제거는 수신자인 우리 책임이다.
 */
@RestController
@RequiredArgsConstructor
public class PointsController {

    public static final String IDEM_KEY = "Idempotency-Key";

    private final LedgerService ledgerService;
    private final IdempotencyManager idempotency;

    @PostMapping("/points/earn")
    @ResponseStatus(HttpStatus.CREATED)
    public EarnResponse earn(
            @RequestHeader(IDEM_KEY) String idemKey,
            @Valid @RequestBody EarnRequest request,
            Authentication caller) {
        return idempotency.execute(idemKey, "/points/earn", request, HttpStatus.CREATED,
                () -> ledgerService.earn(request, caller.getName(), idemKey));
    }

    @PostMapping("/points/redeem")
    public RedeemResponse redeem(
            @RequestHeader(IDEM_KEY) String idemKey,
            @Valid @RequestBody RedeemRequest request,
            Authentication caller) {
        return idempotency.execute(idemKey, "/points/redeem", request, HttpStatus.OK,
                () -> ledgerService.redeem(request, caller.getName(), idemKey));
    }

    /** 사용 취소 — 바디 없으면 전액, {"amount": n}이면 부분 취소 */
    @PostMapping("/points/redeem/{entryId}/cancel")
    public CancelResponse cancel(
            @RequestHeader(IDEM_KEY) String idemKey,
            @PathVariable Long entryId,
            @RequestBody(required = false) @Valid CancelRequest body,
            Authentication caller) {
        // 바디 생략과 {}를 같은 요청으로 정규화 — 멱등 해시가 표현 차이에 흔들리지 않게
        CancelRequest request = body != null ? body : new CancelRequest(null);
        String endpoint = "/points/redeem/" + entryId + "/cancel";
        return idempotency.execute(idemKey, endpoint, request, HttpStatus.OK,
                () -> ledgerService.cancel(entryId, request, caller.getName(), idemKey));
    }
}
