package com.logistics.inventory.domain.common;

import java.util.List;

// nextCursor는 다음 조회에 넘길 값. 다음이 없으면 null
public record CursorResult<T>(
    List<T> items,
    int size,
    boolean hasNext,
    Long nextCursor
) {
}
