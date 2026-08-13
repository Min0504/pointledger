package com.pointledger.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, long expiresInSec) {
    }

    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 64) String name) {
    }

    /** apiKey 원문은 이 응답에서 단 한 번만 노출된다 — 저장은 해시만 */
    public record CreateApiKeyResponse(Long id, String name, String apiKey) {
    }
}
