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
    ENTRY_NOT_FOUND(HttpStatus.NOT_FOUND, "원장 기록을 찾을 수 없습니다."),
    NOT_CANCELLABLE(HttpStatus.UNPROCESSABLE_ENTITY, "사용(REDEEM) 기록만 취소할 수 있습니다."),
    CANCEL_EXCEEDS_REMAINING(HttpStatus.CONFLICT, "취소 가능 잔여를 초과했습니다."),

    // 멱등성
    IDEMPOTENT_IN_PROGRESS(HttpStatus.CONFLICT, "같은 키의 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "같은 멱등성 키로 다른 내용의 요청이 접수됐습니다."),

    // 정산·대사
    MERCHANT_NOT_FOUND(HttpStatus.NOT_FOUND, "가맹점을 찾을 수 없거나 비활성 상태입니다."),
    MERCHANT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 같은 이름의 가맹점이 있습니다."),
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "정산서를 찾을 수 없습니다."),
    ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "대사 이슈를 찾을 수 없습니다."),
    ISSUE_ALREADY_RESOLVED(HttpStatus.CONFLICT, "이미 처리 완료된 이슈입니다."),
    BATCH_JOB_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "배치 잡 실행에 실패했습니다."),

    // 공통
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;
}
