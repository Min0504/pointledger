package com.pointledger.settlement;

import com.pointledger.settlement.dto.SettlementDtos.MerchantCreateRequest;
import com.pointledger.settlement.dto.SettlementDtos.MerchantView;
import com.pointledger.settlement.dto.SettlementDtos.SettlementListResponse;
import com.pointledger.settlement.dto.SettlementDtos.SettlementView;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정산 백오피스 (JWT, ROLE_ADMIN) — 조회·확정은 운영자 행위이므로 감사 주체가
 * 이메일로 남는다. 가맹점 등록은 데모·테스트 픽스처 겸 운영 편의 API.
 */
@RestController
@RequiredArgsConstructor
public class AdminSettlementController {

    private final SettlementAdminService service;

    @PostMapping("/admin/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantView createMerchant(@Valid @RequestBody MerchantCreateRequest request) {
        return service.createMerchant(request.name());
    }

    @GetMapping("/admin/merchants")
    public List<MerchantView> listMerchants() {
        return service.listMerchants();
    }

    @GetMapping("/admin/settlements")
    public SettlementListResponse listSettlements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate,
            @RequestParam(required = false) Long merchantId) {
        return new SettlementListResponse(service.listSettlements(settleDate, merchantId));
    }

    @PostMapping("/admin/settlements/{id}/confirm")
    public SettlementView confirm(@PathVariable Long id, Authentication caller) {
        return service.confirm(id, caller.getName());
    }
}
