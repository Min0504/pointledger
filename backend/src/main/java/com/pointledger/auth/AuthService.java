package com.pointledger.auth;

import com.pointledger.auth.dto.AuthDtos.LoginResponse;
import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final OperatorRepository operatorRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional(readOnly = true)
    public LoginResponse login(String email, String password) {
        Operator operator = operatorRepository.findByEmail(email)
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(password, operator.getPasswordHash())) {
            // 계정 존재 여부와 비밀번호 오류를 같은 응답으로 — 계정 열거(enumeration) 차단
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS);
        }
        return new LoginResponse(jwtProvider.issue(operator), jwtProvider.ttlSeconds());
    }
}
