package com.pointledger.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * API 에러 계약의 단일 원천. 클라이언트(주문 서버·어드민)는 HTTP 상태가 아니라
 * 이 code 문자열로 분기한다 — 상태 코드는 재시도 정책, code는 도메인 의미.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // 지갑
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    WALLET_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 해당 사용자의 지갑이 존재합니다."),

    // 원장
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "잔액이 부족합니다."),
    // [실험 브랜치] 낙관적 락 재시도 소진 — 호출자가 재시도해야 한다는 것 자체가 이 전략의 비용
    CONFLICT_RETRY_EXHAUSTED(HttpStatus.CONFLICT, "동시 요청 경합으로 처리하지 못했습니다. 다시 시도해 주세요."),

    // 공통
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;
}
