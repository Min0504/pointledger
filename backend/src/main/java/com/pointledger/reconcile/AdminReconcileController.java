package com.pointledger.reconcile;

import com.pointledger.reconcile.dto.ReconcileDtos.IssueListResponse;
import com.pointledger.reconcile.dto.ReconcileDtos.IssueView;
import com.pointledger.reconcile.dto.ReconcileDtos.ResolveRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대사 이슈 백오피스 (JWT, ROLE_ADMIN). resolve는 "검토 완료 표시"일 뿐이다 —
 * 잔액을 고치지 않는다. 실제 정정은 /admin/points/grant·revoke가 사유와 함께
 * 원장에 남긴다 (자동 수정 금지 원칙의 API 형태).
 */
@RestController
@RequiredArgsConstructor
public class AdminReconcileController {

    private final ReconcileAdminService service;

    @GetMapping("/admin/reconcile/issues")
    public IssueListResponse issues(@RequestParam(defaultValue = "false") boolean resolved) {
        return new IssueListResponse(service.issues(resolved));
    }

    @PostMapping("/admin/reconcile/issues/{id}/resolve")
    public IssueView resolve(@PathVariable Long id,
            @Valid @RequestBody ResolveRequest request, Authentication caller) {
        return service.resolve(id, request.memo(), caller.getName());
    }
}
