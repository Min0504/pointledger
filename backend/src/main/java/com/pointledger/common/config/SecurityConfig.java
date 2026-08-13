package com.pointledger.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointledger.auth.AdminJwtAuthFilter;
import com.pointledger.auth.ApiKeyAuthFilter;
import com.pointledger.common.error.ErrorCode;
import com.pointledger.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 인증 2계층 — 서버 간(X-API-Key → ROLE_SERVER)과 운영자(JWT → ROLE_ADMIN)를
 * 필터로 식별만 하고, "어느 경로에 무엇이 필요한가"는 아래 인가 규칙 한 곳에 모은다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final AdminJwtAuthFilter adminJwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // 세션 쿠키가 없는 순수 토큰 API — CSRF 표면 자체가 없다
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/actuator/health",
                                "/docs/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // 조회는 겸용 — 어드민의 지갑 검색·원장 타임라인이 같은 읽기 API를 쓴다.
                        // 감사 주체 구분이 중요한 것은 원장에 기록을 남기는 쓰기 경로다
                        .requestMatchers(HttpMethod.GET, "/wallets/**").hasAnyRole("SERVER", "ADMIN")
                        // 쓰기 계열 — 운영자 토큰으로는 호출 불가(호출 주체 감사 구분을 위해 의도적 분리)
                        .requestMatchers("/wallets/**", "/points/**").hasRole("SERVER")
                        .anyRequest().denyAll())
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(adminJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeError(res, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) ->
                                writeError(res, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN)))
                .build();
    }

    /** 시큐리티 계층 거절도 도메인 에러와 같은 JSON 형태 — 클라이언트 분기 로직 단일화 */
    private void writeError(HttpServletResponse res, int status, ErrorCode code)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(code)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
