package com.logistics.inventory.domain.common;

public record CursorQuery(
    Long cursor,
    int size
) {

    public static final int MAX_SIZE = 100;

    public CursorQuery {
        if ((cursor != null && cursor < 1) || size < 1) {
            throw new IllegalArgumentException("cursor는 1 이상, size는 1 이상이어야 합니다. cursor=" + cursor + ", size=" + size);
        }
        size = Math.min(size, MAX_SIZE);
    }
}
