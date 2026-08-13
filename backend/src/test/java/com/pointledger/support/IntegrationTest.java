package com.pointledger.support;

import com.pointledger.auth.ApiKey;
import com.pointledger.auth.ApiKeyRepository;
import com.pointledger.auth.Operator;
import com.pointledger.auth.OperatorRepository;
import com.pointledger.auth.Sha256;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 공통 기반 — 실제 PostgreSQL 16 (Testcontainers).
 * CHECK 제약·부분 유니크 인덱스·append-only 트리거·FOR UPDATE가 검증 대상이라
 * 인메모리 DB로는 테스트가 성립하지 않는다 (기획서 §11).
 *
 * 컨테이너는 싱글턴 패턴으로 직접 기동한다 — @Container 정적 필드는 테스트 클래스가
 * 끝날 때 내려가지만 Spring 컨텍스트 캐시는 클래스를 넘어 살아남으므로, 두 번째
 * 클래스부터 죽은 컨테이너의 포트를 바라보는 사고가 난다. JVM 종료 시 Ryuk이 정리한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.secret", () -> "test-jwt-secret-must-be-32-bytes!!!");
    }

    protected static final String API_KEY_RAW = "plk_test-integration-key";
    protected static final String ADMIN_EMAIL = "ops@pointledger.io";
    protected static final String ADMIN_PASSWORD = "password1234";

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE lot_consumptions, idempotency_requests, point_lots,
                               ledger_entries, wallets, api_keys, operators
                RESTART IDENTITY CASCADE
                """);
        apiKeyRepository.save(new ApiKey("order-server", Sha256.hex(API_KEY_RAW)));
        operatorRepository.save(new Operator(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    // ── HTTP 헬퍼 — 서버 간(X-API-Key)과 운영자(Bearer)를 명시적으로 구분 ──────

    /** 매 호출이 새 UUID 키를 갖는다 — "서로 다른 논리적 요청"이 기본 의미 */
    protected ResponseEntity<Map<String, Object>> serverPost(String path, Map<String, ?> body) {
        return serverPost(path, body, UUID.randomUUID().toString());
    }

    /** 멱등성 시나리오용 — 같은 키를 명시적으로 재사용해 "같은 요청의 재시도"를 표현 */
    protected ResponseEntity<Map<String, Object>> serverPost(
            String path, Map<String, ?> body, String idempotencyKey) {
        HttpHeaders headers = apiKeyHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return exchange(HttpMethod.POST, path, body, headers);
    }

    protected ResponseEntity<Map<String, Object>> serverGet(String path) {
        return exchange(HttpMethod.GET, path, null, apiKeyHeaders());
    }

    protected ResponseEntity<Map<String, Object>> adminPost(String path, Map<String, ?> body, String token) {
        return adminPost(path, body, token, UUID.randomUUID().toString());
    }

    protected ResponseEntity<Map<String, Object>> adminPost(
            String path, Map<String, ?> body, String token, String idempotencyKey) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", idempotencyKey);
        return exchange(HttpMethod.POST, path, body, headers);
    }

    protected ResponseEntity<Map<String, Object>> anonymousPost(String path, Map<String, ?> body) {
        return exchange(HttpMethod.POST, path, body, jsonHeaders());
    }

    protected String adminLogin() {
        ResponseEntity<Map<String, Object>> res = anonymousPost("/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        return (String) res.getBody().get("accessToken");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected ResponseEntity<Map<String, Object>> exchange(
            HttpMethod method, String path, Object body, HttpHeaders headers) {
        ResponseEntity<Map> res =
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity) res;
    }

    protected HttpHeaders apiKeyHeaders() {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-API-Key", API_KEY_RAW);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
