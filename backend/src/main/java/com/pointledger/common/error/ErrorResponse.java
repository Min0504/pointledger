package com.pointledger.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** 모든 에러 응답의 고정 형태 — { code, message, details? } */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.getDefaultMessage(), null);
    }

    public static ErrorResponse of(ErrorCode code, Map<String, Object> details) {
        return new ErrorResponse(code.name(), code.getDefaultMessage(), details);
    }
}
