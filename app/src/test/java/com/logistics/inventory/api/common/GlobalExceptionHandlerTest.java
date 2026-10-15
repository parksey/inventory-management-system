package com.logistics.inventory.api.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

@WebMvcTest(ErrorTestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("TC-20-03 바디 검증에 실패하면 400 BAD_REQUEST와 틀린 항목, 이유를 돌려준다")
    void 바디_검증_실패() throws Exception {
        mockMvc.perform(post("/test/errors/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"\", \"quantity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpectAll(common(400, "BAD_REQUEST"))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.field == 'name')].reason").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.field == 'quantity')].reason").isNotEmpty());
    }

    @Test
    @DisplayName("TC-20-04 파라미터 검증에 실패하면 400 BAD_REQUEST와 파라미터 이름을 돌려준다")
    void 파라미터_검증_실패() throws Exception {
        mockMvc.perform(get("/test/errors/param").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpectAll(common(400, "BAD_REQUEST"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].field").value("size"))
                .andExpect(jsonPath("$.data[0].reason").isNotEmpty());
    }

    @Test
    @DisplayName("TC-20-05 깨진 JSON은 400 BAD_REQUEST이고 파서 메시지를 내보내지 않는다")
    void 깨진_JSON() throws Exception {
        mockMvc.perform(post("/test/errors/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpectAll(common(400, "BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Bad Request"));
    }

    @Test
    @DisplayName("TC-20-06 타입이 틀린 파라미터는 400 BAD_REQUEST")
    void 파라미터_타입_불일치() throws Exception {
        mockMvc.perform(get("/test/errors/page").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpectAll(common(400, "BAD_REQUEST"));
    }

    @Test
    @DisplayName("TC-20-07 없는 경로는 404 NOT_FOUND")
    void 없는_경로() throws Exception {
        mockMvc.perform(get("/test/errors/not-exist"))
                .andExpect(status().isNotFound())
                .andExpectAll(common(404, "NOT_FOUND"));
    }

    @Test
    @DisplayName("TC-20-08 다른 메서드로 부르면 405 METHOD_NOT_ALLOWED와 Allow 헤더")
    void 메서드_불일치() throws Exception {
        mockMvc.perform(get("/test/errors/body"))
                .andExpect(status().isMethodNotAllowed())
                .andExpectAll(common(405, "METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Allow", "POST"));
    }

    @Test
    @DisplayName("TC-20-09 지원하지 않는 Content-Type은 415 UNSUPPORTED_MEDIA_TYPE")
    void 미디어_타입_불일치() throws Exception {
        mockMvc.perform(post("/test/errors/body").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpectAll(common(415, "UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("TC-20-10 락 대기가 초과되면 503 SERVICE_UNAVAILABLE과 Retry-After")
    void 락_대기_초과() throws Exception {
        mockMvc.perform(get("/test/errors/lock"))
                .andExpect(status().isServiceUnavailable())
                .andExpectAll(common(503, "SERVICE_UNAVAILABLE"))
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    @DisplayName("TC-20-11 예상하지 못한 예외는 500 INTERNAL_SERVER_ERROR이고 내부 정보를 내보내지 않는다")
    void 예상하지_못한_예외() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpectAll(common(500, "INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal Server Error"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    // R1 공통 형식. code는 외부 계약이라 enum이 아니라 문자열 리터럴로 비교한다
    private static ResultMatcher[] common(final int status, final String code) {
        return new ResultMatcher[] {
                content().contentType(MediaType.APPLICATION_JSON),
                jsonPath("$.status").value(status),
                jsonPath("$.code").value(code),
                jsonPath("$.message").isNotEmpty(),
                jsonPath("$", hasKey("data"))
        };
    }
}
