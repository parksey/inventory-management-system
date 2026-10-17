package com.logistics.inventory.api.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 아직 실제 API가 없는 에러 경우를 만들어 내는 테스트 전용 컨트롤러
@RestController
@RequestMapping("/test/errors")
class ErrorTestController {

    record SampleRequest(@NotBlank String name, @Positive int quantity) {
    }

    @PostMapping("/body")
    void body(@Valid @RequestBody final SampleRequest request) {
    }

    @GetMapping("/param")
    void param(@RequestParam @Max(100) final int size) {
    }

    @GetMapping("/page")
    void page(@RequestParam final int page) {
    }

    @GetMapping("/lock")
    void lock() {
        throw new CannotAcquireLockException("canceling statement due to lock timeout");
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("password=secret");
    }
}
