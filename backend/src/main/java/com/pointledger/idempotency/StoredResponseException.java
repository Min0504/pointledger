package com.pointledger.idempotency;

import lombok.Getter;

/**
 * 완료된 멱등 요청의 재생 신호 — GlobalExceptionHandler가 저장된 상태·바디를
 * 그대로 응답한다. 예외로 구현한 이유: 컨트롤러·서비스 어느 깊이에서든
 * 정상 흐름을 끊고 저장된 응답으로 단락(short-circuit)하기 위해.
 */
@Getter
public class StoredResponseException extends RuntimeException {

    private final int status;
    private final String bodyJson;

    public StoredResponseException(int status, String bodyJson) {
        super("idempotent replay: " + status);
        this.status = status;
        this.bodyJson = bodyJson;
    }
}
