package com.pointledger.ledger;

import static com.pointledger.ledger.PointsController.IDEM_KEY;

import com.pointledger.idempotency.IdempotencyManager;
import com.pointledger.ledger.dto.LedgerDtos.GrantRequest;
import com.pointledger.ledger.dto.LedgerDtos.GrantResponse;
import com.pointledger.ledger.dto.LedgerDtos.RevokeRequest;
import com.pointledger.ledger.dto.LedgerDtos.RevokeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 수동 지급/회수 (JWT, ROLE_ADMIN) — CS 보상 등 (기획서 §2).
 *
 * 감사 3요소를 강제한다: 누가(created_by = 운영자 이메일, JWT에서 추출),
 * 언제(created_at), 왜(reason — 스키마 CHECK가 최후 방어). 서버 간 API와
 * 인증을 분리한 이유가 이것이다 — API 키 이름과 운영자 이메일이 원장에서
 * 섞이면 "누가 조작했나"에 답할 수 없다.
 */
@RestController
@RequiredArgsConstructor
public class AdminPointsController {

    private final LedgerService ledgerService;
    private final IdempotencyManager idempotency;

    @PostMapping("/admin/points/grant")
    @ResponseStatus(HttpStatus.CREATED)
    public GrantResponse grant(
            @RequestHeader(IDEM_KEY) String idemKey,
            @Valid @RequestBody GrantRequest request,
            Authentication caller) {
        return idempotency.execute(idemKey, "/admin/points/grant", request, HttpStatus.CREATED,
                () -> ledgerService.grant(request, caller.getName(), idemKey));
    }

    @PostMapping("/admin/points/revoke")
    public RevokeResponse revoke(
            @RequestHeader(IDEM_KEY) String idemKey,
            @Valid @RequestBody RevokeRequest request,
            Authentication caller) {
        return idempotency.execute(idemKey, "/admin/points/revoke", request, HttpStatus.OK,
                () -> ledgerService.revoke(request, caller.getName(), idemKey));
    }
}
