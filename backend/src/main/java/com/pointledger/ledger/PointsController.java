package com.pointledger.ledger;

import com.pointledger.ledger.dto.LedgerDtos.EarnRequest;
import com.pointledger.ledger.dto.LedgerDtos.EarnResponse;
import com.pointledger.ledger.dto.LedgerDtos.RedeemRequest;
import com.pointledger.ledger.dto.LedgerDtos.RedeemResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 서버 간 API (X-API-Key) — 적립/사용. 호출 주체(키 이름)가 원장 created_by로 남는다 */
@RestController
@RequiredArgsConstructor
public class PointsController {

    private final LedgerService ledgerService;

    @PostMapping("/points/earn")
    @ResponseStatus(HttpStatus.CREATED)
    public EarnResponse earn(@Valid @RequestBody EarnRequest request, Authentication caller) {
        return ledgerService.earn(request, caller.getName());
    }

    @PostMapping("/points/redeem")
    public RedeemResponse redeem(@Valid @RequestBody RedeemRequest request, Authentication caller) {
        return ledgerService.redeem(request, caller.getName());
    }
}
