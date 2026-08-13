package com.pointledger.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 서버 간 인증 — X-API-Key 헤더의 SHA-256 해시로 활성 키를 찾는다.
 * 키가 없거나 틀리면 여기서 거절하지 않고 통과시킨다 — 어떤 경로에 어떤 인증이
 * 필요한지는 SecurityConfig의 인가 규칙 한 곳에서만 판정한다(규칙 분산 방지).
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw != null && !raw.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            apiKeyRepository.findByKeyHashAndActiveTrue(Sha256.hex(raw)).ifPresent(key -> {
                // principal = 키 이름 — 원장 created_by에 그대로 기록되어 감사 추적의 시작점이 된다
                var auth = new UsernamePasswordAuthenticationToken(
                        key.getName(), null, List.of(new SimpleGrantedAuthority("ROLE_SERVER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
