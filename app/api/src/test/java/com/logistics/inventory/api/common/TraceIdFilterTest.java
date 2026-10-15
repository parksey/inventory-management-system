package com.logistics.inventory.api.common;

import static com.logistics.inventory.api.common.TraceIdFilter.MDC_KEY;
import static com.logistics.inventory.api.common.TraceIdFilter.TRACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inventory");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> traceIdInChain = new AtomicReference<>();
    private final FilterChain capturingChain = (req, res) -> traceIdInChain.set(MDC.get(MDC_KEY));

    @Test
    @DisplayName("TC-1-15 X-Request-Id가 없으면 새 traceId를 만들어 요청 처리 중 MDC와 응답 헤더에 넣는다")
    void 헤더_없음_생성() throws Exception {
        filter.doFilter(request, response, capturingChain);

        final String traceId = response.getHeader(TRACE_HEADER);
        assertThat(UUID.fromString(traceId)).isNotNull();
        assertThat(traceIdInChain.get()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("TC-1-16 형식에 맞는 X-Request-Id가 오면 그 값을 이어 쓴다")
    void 헤더_이어쓰기() throws Exception {
        request.addHeader(TRACE_HEADER, "gw-1234_abcd");

        filter.doFilter(request, response, capturingChain);

        assertThat(response.getHeader(TRACE_HEADER)).isEqualTo("gw-1234_abcd");
        assertThat(traceIdInChain.get()).isEqualTo("gw-1234_abcd");
    }

    @Test
    @DisplayName("TC-1-17 개행이 섞이거나 64자를 넘는 X-Request-Id는 버리고 새로 만든다")
    void 잘못된_헤더_교체() throws Exception {
        for (final String invalid : new String[]{"abc\nINFO fake log", "a".repeat(65), "한글", " "}) {
            final MockHttpServletRequest invalidRequest = new MockHttpServletRequest("GET", "/");
            final MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
            invalidRequest.addHeader(TRACE_HEADER, invalid);

            filter.doFilter(invalidRequest, invalidResponse, capturingChain);

            assertThat(UUID.fromString(invalidResponse.getHeader(TRACE_HEADER))).as(invalid).isNotNull();
        }
    }

    @Test
    @DisplayName("TC-1-18 요청이 끝나면 예외가 나도 MDC에서 traceId를 지운다")
    void MDC_정리() {
        final FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(MDC_KEY)).isNull();
    }
}
