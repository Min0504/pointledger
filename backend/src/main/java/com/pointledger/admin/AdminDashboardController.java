package com.pointledger.admin;

import com.pointledger.admin.dto.DashboardDtos.DashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 어드민 대시보드 (JWT, ROLE_ADMIN) — 오늘의 흐름과 처리 대기 신호 한 장 */
@RestController
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService service;

    @GetMapping("/admin/dashboard")
    public DashboardResponse dashboard() {
        return service.today();
    }
}
