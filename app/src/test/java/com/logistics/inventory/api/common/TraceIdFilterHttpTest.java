package com.logistics.inventory.api.common;

import static com.logistics.inventory.api.common.TraceIdFilter.TRACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.logistics.inventory.api.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TraceIdFilterHttpTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("TC-1-19 실제 요청의 X-Request-Id를 응답 헤더로 돌려준다")
    void 실제_요청_헤더_이어쓰기() {
        final ResponseEntity<Void> response = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .header(TRACE_HEADER, "demo-trace-001")
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getHeaders().getFirst(TRACE_HEADER)).isEqualTo("demo-trace-001");
    }
}
