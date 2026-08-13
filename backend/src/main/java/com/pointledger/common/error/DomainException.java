package com.pointledger.common.error;

import java.util.Map;
import lombok.Getter;

/** 도메인 규칙 위반. GlobalExceptionHandler가 ErrorCode의 상태·메시지로 변환한다. */
@Getter
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public DomainException(ErrorCode code) {
        this(code, null);
    }

    public DomainException(ErrorCode code, Map<String, Object> details) {
        super(code.getDefaultMessage());
        this.code = code;
        this.details = details;
    }
}
