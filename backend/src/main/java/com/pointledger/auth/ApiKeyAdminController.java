package com.pointledger.auth;

import com.pointledger.auth.dto.AuthDtos.CreateApiKeyRequest;
import com.pointledger.auth.dto.AuthDtos.CreateApiKeyResponse;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API 키 발급 — 운영자 전용. 원문은 응답에서 한 번만 노출되고 해시만 남는다. */
@RestController
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;

    @PostMapping("/admin/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateApiKeyResponse create(@Valid @RequestBody CreateApiKeyRequest request) {
        byte[] bytes = new byte[32]; // 256bit 무작위 — 추측 불가, 솔트 불필요의 근거
        RANDOM.nextBytes(bytes);
        String raw = "plk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ApiKey saved = apiKeyRepository.save(new ApiKey(request.name(), Sha256.hex(raw)));
        return new CreateApiKeyResponse(saved.getId(), saved.getName(), raw);
    }
}
