package com.logistics.inventory.api.common;

import static com.logistics.inventory.api.common.TraceIdFilter.TRACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.logistics.inventory.api.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

// prod 설정 그대로 띄운다. prod는 마이그레이션을 flyway 이미지에 맡기므로 테스트에서만 앱이 적용하게 켠다
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true"
)
@ActiveProfiles({"test", "prod"})
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ProdLogFormatTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("TC-22-01 prod 프로파일의 로그는 한 줄이 JSON이고 그 요청의 traceId 필드가 있다")
    void prod_JSON_로그(final CapturedOutput output) {
        RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build()
                .get().uri("/api/not-found")   // 404 요청 오류 WARN 로그가 남는다
                .header(TRACE_HEADER, "trace-22-01")
                .retrieve().toBodilessEntity();

        assertThat(output.getOut().lines().filter(line -> line.contains("trace-22-01")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(JsonPath.<String>read(line, "$.traceId")).isEqualTo("trace-22-01"));
    }
}
