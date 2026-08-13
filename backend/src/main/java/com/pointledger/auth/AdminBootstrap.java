package com.pointledger.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 최초 운영자 계정 생성. 내부 백오피스라 셀프 가입 API가 없으므로,
 * 부팅 시 환경변수(PL_ADMIN_EMAIL/PASSWORD)로 1회 시드한다.
 * 비밀번호가 비어 있으면 건너뛴다 — 테스트·CI에서는 시드 없이 뜨게.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdminBootstrap {

    @Bean
    public ApplicationRunner seedAdmin(
            OperatorRepository operators,
            PasswordEncoder encoder,
            @Value("${pointledger.admin.email}") String email,
            @Value("${pointledger.admin.password}") String password) {
        return args -> {
            if (password == null || password.isBlank()) {
                log.info("운영자 시드 생략 — PL_ADMIN_PASSWORD 미설정");
                return;
            }
            if (operators.findByEmail(email).isPresent()) {
                return;
            }
            operators.save(new Operator(email, encoder.encode(password)));
            log.info("운영자 계정 생성: {}", email);
        };
    }
}
