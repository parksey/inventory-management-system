package com.logistics.inventory.api.common;

import com.logistics.inventory.domain.common.CursorResult;
import java.util.List;
import java.util.function.Function;

// 이어서 조회하는(무한 스크롤) API의 공통 응답. 항목 타입만 API마다 다르다
public record CursorResponse<T>(
    List<T> items,
    int size,
    boolean hasNext,
    Long nextCursor
) {

    public static <E, T> CursorResponse<T> of(final CursorResult<E> result, final Function<? super E, ? extends T> mapper) {
        final List<T> items = result.items().stream().<T>map(mapper).toList();
        return new CursorResponse<>(items, result.size(), result.hasNext(), result.nextCursor());
    }
}
