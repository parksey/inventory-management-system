package com.logistics.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.logistics.inventory.api.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class InventoryApplicationTests {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("TC-1-01 앱이 기동하고 /actuator/health가 HTTP 200과 UP을 돌려준다")
    void health_UP() {
        final ResponseEntity<String> response = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
